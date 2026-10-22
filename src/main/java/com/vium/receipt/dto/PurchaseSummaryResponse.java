package com.vium.receipt.dto;

import com.vium.receipt.entity.PurchaseStatus;
import com.vium.receipt.entity.PurchaseType;
import java.time.OffsetDateTime;

public record PurchaseSummaryResponse(Long purchaseId, PurchaseType type, PurchaseStatus status,
	String purchaseSourceName, OffsetDateTime purchasedAt, Long totalAmount, long itemCount) {
}
