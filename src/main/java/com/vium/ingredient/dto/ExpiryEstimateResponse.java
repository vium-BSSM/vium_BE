package com.vium.ingredient.dto;

import java.time.LocalDate;

public record ExpiryEstimateResponse(
	Long ingredientCatalogId,
	String ingredientName,
	Short storageMethodId,
	String storageMethodName,
	LocalDate purchasedOn,
	Integer shelfLifeDays,
	LocalDate estimatedExpiryDate
) {
}
