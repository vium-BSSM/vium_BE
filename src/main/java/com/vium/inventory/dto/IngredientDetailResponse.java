package com.vium.inventory.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record IngredientDetailResponse(
	Long inventoryItemId,
	Long ingredientCatalogId,
	String name,
	String categoryName,
	BigDecimal initialQuantity,
	BigDecimal remainingQuantity,
	Long amount,
	Short unitId,
	String unit,
	Short storageMethodId,
	String storageMethodName,
	String statusCode,
	LocalDate purchasedOn,
	LocalDate expiresOn
) {
}
