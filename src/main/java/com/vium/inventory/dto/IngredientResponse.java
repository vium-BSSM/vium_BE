package com.vium.inventory.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record IngredientResponse(
	Long inventoryItemId,
	Long ingredientCatalogId,
	String customName,
	BigDecimal initialQuantity,
	BigDecimal remainingQuantity,
	Short unitId,
	Short storageMethodId,
	String statusCode,
	LocalDate purchasedOn,
	LocalDate expiresOn
) {
}
