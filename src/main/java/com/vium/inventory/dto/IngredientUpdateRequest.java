package com.vium.inventory.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public record IngredientUpdateRequest(
	@JsonProperty(required = true) @Size(max = 120) String customName,
	@NotNull @DecimalMin("0.001") @Digits(integer = 9, fraction = 3) BigDecimal quantity,
	@NotNull Short unitId,
	@NotNull Short storageMethodId,
	@NotNull @PastOrPresent LocalDate purchasedOn,
	@NotNull LocalDate expiresOn
) {
}
