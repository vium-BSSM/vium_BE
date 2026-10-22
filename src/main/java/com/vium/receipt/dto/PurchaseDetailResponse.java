package com.vium.receipt.dto;

import com.vium.receipt.entity.PurchaseStatus;
import com.vium.receipt.entity.PurchaseType;
import java.time.OffsetDateTime;
import java.util.List;

public record PurchaseDetailResponse(Long purchaseId, PurchaseType type, PurchaseStatus status,
		Long imageId, Short purchaseSourceId, String purchaseSourceName, OffsetDateTime purchasedAt,
		Long totalAmount, List<PurchaseItemDraftResponse> items, OffsetDateTime createdAt) {
	public PurchaseDetailResponse {
		items = List.copyOf(items);
	}
}
