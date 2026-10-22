package com.vium.receipt.parser;

import com.vium.receipt.entity.PurchaseType;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class PurchaseSourceDetector {

	private static final Pattern CU = Pattern.compile("(?<![a-z])cu(?![a-z])", Pattern.CASE_INSENSITIVE);
	private static final Pattern MARKET = Pattern.compile("시장(?![가-힣a-zA-Z])");

	public String detect(List<OcrRowAssembler.Row> rows, PurchaseType type) {
		String original = String.join("\n", rows.stream().map(OcrRowAssembler.Row::text).toList());
		String text = original.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
		if (text.contains("이마트24")) return "convenience";
		if (containsAny(text, "이마트", "emart")) return "emart";
		if (containsAny(text, "쿠팡", "coupang")) return "coupang";
		if (CU.matcher(original).find() || containsAny(text, "gs25", "세븐일레븐", "7-eleven", "미니스톱")) return "convenience";
		if (rows.stream().map(OcrRowAssembler.Row::text)
			.filter(row -> !containsAny(row, "주소", "도로명", "소재지"))
			.anyMatch(row -> MARKET.matcher(row).find())) return "market";
		return type == PurchaseType.RECEIPT ? "etc" : "online";
	}

	private boolean containsAny(String text, String... words) {
		for (String word : words) {
			if (text.contains(word)) return true;
		}
		return false;
	}
}
