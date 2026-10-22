package com.vium.receipt.parser;

import com.vium.receipt.entity.PurchaseType;
import java.util.List;
import java.util.Locale;

public class PurchaseSourceDetector {

	public String detect(List<OcrRowAssembler.Row> rows, PurchaseType type) {
		String text = String.join("\n", rows.stream().map(OcrRowAssembler.Row::text).toList())
			.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
		if (text.contains("이마트24")) return "convenience";
		if (containsAny(text, "이마트", "emart")) return "emart";
		if (containsAny(text, "쿠팡", "coupang")) return "coupang";
		if (containsAny(text, "gs25", "cu", "세븐일레븐", "7-eleven", "미니스톱")) return "convenience";
		if (text.contains("시장")) return "market";
		return type == PurchaseType.RECEIPT ? "etc" : "online";
	}

	private boolean containsAny(String text, String... words) {
		for (String word : words) {
			if (text.contains(word)) return true;
		}
		return false;
	}
}
