package com.vium.receipt.parser;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Pattern;

public class ItemNameQuantityParser {

	private static final Pattern CAPACITY = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(kg|g|ml|l|리터)", Pattern.CASE_INSENSITIVE);
	private static final Pattern COUNT = Pattern.compile("(\\d+)\\s*(구|개입|입|개|알|봉|팩|병|캔|통)");
	private static final Pattern MULTIPLIER = Pattern.compile("[xX×*]\\s*(\\d+)");

	public Quantity parse(String name, BigDecimal purchaseQty) {
		var multiplier = MULTIPLIER.matcher(name);
		BigDecimal factor = multiplier.find() ? new BigDecimal(multiplier.group(1)) : BigDecimal.ONE;
		var capacity = CAPACITY.matcher(name);
		if (capacity.find()) {
			short unitId = switch (capacity.group(2).toLowerCase(Locale.ROOT)) {
				case "g" -> 2;
				case "kg" -> 3;
				case "ml" -> 4;
				default -> 5;
			};
			return new Quantity(new BigDecimal(capacity.group(1)).multiply(factor).multiply(purchaseQty), unitId);
		}
		var count = COUNT.matcher(name);
		if (count.find()) {
			short unitId = switch (count.group(2)) {
				case "팩" -> 6;
				case "봉" -> 7;
				case "병" -> 8;
				case "캔" -> 9;
				case "통" -> 10;
				default -> 1;
			};
			return new Quantity(new BigDecimal(count.group(1)).multiply(factor).multiply(purchaseQty), unitId);
		}
		return new Quantity(purchaseQty, (short) 1);
	}

	public record Quantity(BigDecimal quantity, Short unitId) {
	}
}
