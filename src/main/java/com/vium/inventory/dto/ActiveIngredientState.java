package com.vium.inventory.dto;

import java.math.BigDecimal;

public record ActiveIngredientState(
	Long ingredientCatalogId,
	String name,
	Short unitId,
	BigDecimal remainingQuantity
) {
}
