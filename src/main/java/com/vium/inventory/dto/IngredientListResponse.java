package com.vium.inventory.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record IngredientListResponse(List<Item> ingredients) {

	public record Item(
		Long inventoryItemId,
		String name,
		LocalDate expiresOn,
		BigDecimal remainingQuantity,
		Short storageMethodId,
		String storageMethodName
	) {
	}
}
