package com.vium.receipt.dto;

import com.vium.receipt.entity.PurchaseStatus;
import com.vium.receipt.entity.PurchaseType;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PurchaseSummary(Long purchaseId, PurchaseType type, PurchaseStatus status,
		String purchaseSourceName, LocalDateTime purchasedAt, BigDecimal totalAmount, long itemCount) {
}
