package com.vium.inventory.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public record IngredientRegisterRequest(
	Long ingredientCatalogId,
	@Size(max = 120) String customName,
	@NotNull @DecimalMin("0.001") @Digits(integer = 9, fraction = 3) BigDecimal quantity,
	@NotNull Short unitId,
	Short storageMethodId,
	@PastOrPresent LocalDate purchasedOn,
	LocalDate expiresOn,
	@PositiveOrZero Integer amount
) {
}
