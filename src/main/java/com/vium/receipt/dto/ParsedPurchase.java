package com.vium.receipt.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 찾지 못한 날짜·금액은 null로 남기고 scan 서비스에서 정규화한다. */
public record ParsedPurchase(String purchaseSourceCode, LocalDateTime purchasedAt,
		BigDecimal totalAmount, List<ParsedItem> items) {
	public ParsedPurchase {
		items = List.copyOf(items);
	}
}
