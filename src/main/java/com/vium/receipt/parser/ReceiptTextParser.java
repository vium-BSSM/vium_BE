package com.vium.receipt.parser;

import com.vium.receipt.dto.ParsedItem;
import com.vium.receipt.dto.ParsedPurchase;
import com.vium.receipt.entity.PurchaseType;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

public class ReceiptTextParser {

	private final Clock clock;
	private final ItemNameQuantityParser quantities = new ItemNameQuantityParser();
	private final PurchaseSourceDetector sources = new PurchaseSourceDetector();

	public ReceiptTextParser(Clock clock) {
		this.clock = clock;
	}

	public ParsedPurchase parse(List<OcrRowAssembler.Row> rows) {
		var texts = rows.stream().map(OcrRowAssembler.Row::text).toList();
		BigDecimal total = null;
		int start = 0;
		int end = rows.size();
		for (int i = 0; i < texts.size(); i++) {
			String text = texts.get(i);
			if (OcrPatterns.receiptTotal(text)) {
				var amount = OcrPatterns.lastAmount(text, false);
				if (amount != null && (total == null || amount.compareTo(total) > 0)) total = amount;
			}
			if (end == rows.size() && OcrPatterns.receiptEnd(text)) end = i;
			if (i < end && OcrPatterns.header(text)) start = i + 1;
		}
		List<ParsedItem> items = new ArrayList<>();
		boolean previousWasItem = false;
		for (int i = start; i < end; i++) {
			String text = texts.get(i).strip();
			BigDecimal discount = OcrPatterns.lastAmount(text, false);
			boolean discountRow = text.contains("할인") || text.matches("-\\d[\\d,]*\\s*원?");
			if (discountRow) {
				if (previousWasItem && discount != null) {
					var item = items.getLast();
					items.set(items.size() - 1, new ParsedItem(item.rawName(), item.quantity(), item.unitId(),
						item.unitPrice(), item.amount().subtract(discount.abs()).max(BigDecimal.ZERO)));
				}
				continue;
			}
			previousWasItem = false;
			if (OcrPatterns.excluded(text, true) || OcrPatterns.hasDate(text)) continue;
			if (OcrPatterns.hasLetters(text) && OcrPatterns.trailingNumbers(text).numbers().isEmpty() && i + 1 < end) {
				String next = texts.get(i + 1).strip();
				if (next.matches("[-\\d,\\s]+") && OcrPatterns.hasDigits(next)) {
					text += " " + OcrPatterns.withoutBarcodes(next);
					i++;
				}
			}
			var tail = OcrPatterns.trailingNumbers(text);
			if (tail.numbers().isEmpty() || !OcrPatterns.hasLetters(tail.name())) continue;
			String name = tail.name().replaceFirst("^[*#\\s]+", "")
				.replaceFirst("^\\d{1,3}\\s+", "")
				.replaceFirst("^[*#\\s]+", "").strip();
			if (!OcrPatterns.hasLetters(name)) continue;
			var numbers = tail.numbers();
			BigDecimal purchaseQty = BigDecimal.ONE;
			BigDecimal unitPrice = null;
			if (numbers.size() == 3) {
				unitPrice = numbers.getFirst();
				if (validPurchaseQty(numbers.get(1))) purchaseQty = numbers.get(1);
			} else if (numbers.size() == 2) {
				if (validPurchaseQty(numbers.getFirst())) purchaseQty = numbers.getFirst();
				else unitPrice = numbers.getFirst();
			}
			var quantity = quantities.parse(name, purchaseQty);
			// 묶음 수량의 *는 곱하기 표기로 보존하고 나머지 장식 기호는 제거한다.
			name = name.replaceAll("\\*(?=\\s*\\d)", "×").replaceAll("[*#]", "").strip();
			items.add(new ParsedItem(name, quantity.quantity(), quantity.unitId(), unitPrice, numbers.getLast()));
			previousWasItem = true;
		}
		return new ParsedPurchase(sources.detect(rows, PurchaseType.RECEIPT),
			OcrPatterns.firstDate(texts, true, clock), total, items);
	}

	private boolean validPurchaseQty(BigDecimal value) {
		return value.compareTo(BigDecimal.ONE) >= 0 && value.compareTo(BigDecimal.valueOf(99)) <= 0;
	}
}
