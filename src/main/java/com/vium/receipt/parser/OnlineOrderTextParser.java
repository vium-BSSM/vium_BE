package com.vium.receipt.parser;

import com.vium.receipt.dto.ParsedItem;
import com.vium.receipt.dto.ParsedPurchase;
import com.vium.receipt.entity.PurchaseType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class OnlineOrderTextParser {

	private static final Pattern COUNT = Pattern.compile("^(\\d+)\\s*개$");
	private static final Pattern INLINE_COUNT = Pattern.compile("(?<![\\p{L}\\d.,])(\\d+)\\s*개(?![\\p{L}\\d])");
	private final Clock clock;
	private final ItemNameQuantityParser quantities = new ItemNameQuantityParser();
	private final PurchaseSourceDetector sources = new PurchaseSourceDetector();

	public OnlineOrderTextParser(Clock clock) {
		this.clock = clock;
	}

	public ParsedPurchase parse(List<OcrRowAssembler.Row> rows) {
		var texts = rows.stream().map(OcrRowAssembler.Row::text).toList();
		List<Candidate> candidates = new ArrayList<>();
		List<String> name = new ArrayList<>();
		BigDecimal total = null;
		Candidate last = null;
		for (String text : texts) {
			String compact = OcrPatterns.compact(text);
			if (compact.matches(".*(?:취소완료|반품완료|취소됨).*")) {
				if (last != null) candidates.remove(last);
				last = null;
				name.clear();
				continue;
			}
			if (OcrPatterns.onlineTotal(text)) {
				var amount = OcrPatterns.lastAmount(text, false);
				if (amount != null) total = amount;
				name.clear();
				last = null;
				continue;
			}
			if (OcrPatterns.excluded(text, false) || OcrPatterns.hasDate(text)) {
				name.clear();
				continue;
			}
			var count = COUNT.matcher(text.strip());
			if (count.matches() && name.isEmpty()) {
				if (last != null) last.purchaseQty = new BigDecimal(count.group(1));
				continue;
			}
			var amount = OcrPatterns.lastAmount(text, true);
			if (OcrPatterns.hasWonAmount(text)) {
				last = null;
				if (amount != null && !name.isEmpty()) {
					last = new Candidate(String.join(" ", name), amount);
					var inlineCount = INLINE_COUNT.matcher(text);
					if (inlineCount.find()) last.purchaseQty = new BigDecimal(inlineCount.group(1));
					candidates.add(last);
				}
				name.clear();
			} else if (OcrPatterns.hasLetters(text)) {
				name.add(text.strip());
				if (name.size() > 2) name.removeFirst();
			}
		}
		var items = candidates.stream().map(candidate -> {
			var quantity = quantities.parse(candidate.name, candidate.purchaseQty);
			return new ParsedItem(candidate.name, quantity.quantity(), quantity.unitId(), null, candidate.amount);
		}).toList();
		return new ParsedPurchase(sources.detect(rows, PurchaseType.ONLINE_ORDER), purchasedAt(texts), total, items);
	}

	private LocalDateTime purchasedAt(List<String> texts) {
		List<String> nearOrder = new ArrayList<>();
		for (int i = 0; i < texts.size(); i++) {
			if (texts.get(i).contains("주문")) {
				nearOrder.add(texts.get(i));
				if (i + 1 < texts.size()) nearOrder.add(texts.get(i + 1));
				if (i > 0) nearOrder.add(texts.get(i - 1));
			}
		}
		var preferred = OcrPatterns.firstDate(nearOrder, false, clock);
		return preferred != null ? preferred : OcrPatterns.firstDate(texts, false, clock);
	}

	private static class Candidate {
		private final String name;
		private final BigDecimal amount;
		private BigDecimal purchaseQty = BigDecimal.ONE;

		private Candidate(String name, BigDecimal amount) {
			this.name = name;
			this.amount = amount;
		}
	}
}
