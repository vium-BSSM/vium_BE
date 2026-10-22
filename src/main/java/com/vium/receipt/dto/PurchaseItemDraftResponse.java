package com.vium.receipt.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PurchaseItemDraftResponse(Long purchaseItemId, String rawName, Long ingredientCatalogId,
		String ingredientName, BigDecimal quantity, Short unitId, Short storageMethodId,
		Long unitPrice, Long amount, LocalDate expiresOn) {
}
