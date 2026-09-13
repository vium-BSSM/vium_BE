package com.vium.inventory.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record IngredientListResponse(List<Item> ingredients) {

	public record Item(
		Long inventoryItemId,
		Long ingredientCatalogId,
		String name,
		String categoryName,
		BigDecimal initialQuantity,
		BigDecimal remainingQuantity,
		Short unitId,
		String unit,
		String statusCode,
		LocalDate purchasedOn,
		LocalDate expiresOn
	) {
	}
}
