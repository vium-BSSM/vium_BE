package com.vium.receipt.service;

import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.receipt.dto.ParsedItem;
import com.vium.receipt.dto.ParsedPurchase;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.regex.Pattern;

/** OCR 결과를 DB에 저장할 수 있는 범위로 제한한다. */
class PurchaseScanNormalizer {
	private static final BigDecimal MAX_QUANTITY = new BigDecimal("999999999.999");
	private static final BigDecimal MAX_AMOUNT = new BigDecimal("100000000");
	private static final Pattern LETTER = Pattern.compile("[가-힣a-zA-Z]");

	ParsedPurchase normalize(ParsedPurchase parsed, LocalDateTime now) {
		var items = new ArrayList<ParsedItem>();
		for (var item : parsed.items()) {
			String name = item.rawName().strip();
			int length = name.codePointCount(0, name.length());
			if (length > 200) name = name.substring(0, name.offsetByCodePoints(0, 200));
			if (name.codePointCount(0, name.length()) < 2 || !LETTER.matcher(name).find()) continue;
			BigDecimal quantity = item.quantity().setScale(3, RoundingMode.HALF_UP);
			if (quantity.signum() <= 0) continue;
			Short unitId = item.unitId();
			if (quantity.compareTo(MAX_QUANTITY) > 0) {
				quantity = new BigDecimal("1.000");
				unitId = 1;
			}
			items.add(new ParsedItem(name, quantity, unitId, amount(item.unitPrice()), amount(item.amount())));
			if (items.size() == 100) break;
		}
		if (items.isEmpty()) throw new BusinessException(ErrorCode.SCAN_UNRECOGNIZABLE);
		BigDecimal total = amount(parsed.totalAmount());
		if (total == null) {
			for (var item : items) {
				if (item.amount() != null) total = total == null ? item.amount() : total.add(item.amount());
			}
			total = amount(total);
		}
		var purchasedAt = parsed.purchasedAt();
		if (purchasedAt == null || purchasedAt.isAfter(now)) purchasedAt = now;
		return new ParsedPurchase(parsed.purchaseSourceCode(), purchasedAt, total, items);
	}

	private BigDecimal amount(BigDecimal value) {
		return value == null || value.signum() < 0 || value.compareTo(MAX_AMOUNT) > 0 ? null : value;
	}
}
