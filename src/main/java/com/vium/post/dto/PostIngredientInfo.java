package com.vium.post.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PostIngredientInfo(
	Long inventoryItemId,
	Long ingredientCatalogId,
	String name,
	String categoryName,
	BigDecimal quantity,
	Short unitId,
	String unit,
	LocalDate expiresOn,
	Short storageMethodId,
	String storageMethodName,
	Boolean isOpened
) {
}
