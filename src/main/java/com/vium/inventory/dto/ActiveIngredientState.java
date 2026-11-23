package com.vium.inventory.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ActiveIngredientState(
	Long ingredientCatalogId,
	String name,
	Short unitId,
	String unitName,
	BigDecimal remainingQuantity,
	LocalDate purchasedOn
) {
}
