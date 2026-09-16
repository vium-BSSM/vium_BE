package com.vium.inventory.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record IngredientUpdateResponse(
	Long inventoryItemId,
	String customName,
	BigDecimal quantity,
	Short unitId,
	Short storageMethodId,
	LocalDate purchasedOn,
	LocalDate expiresOn,
	String statusCode
) {
}
