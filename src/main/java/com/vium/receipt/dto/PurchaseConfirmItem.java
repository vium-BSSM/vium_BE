package com.vium.receipt.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;

public record PurchaseConfirmItem(
	Long purchaseItemId, Long ingredientCatalogId, @Size(max = 120) String customName,
	@NotNull @DecimalMin("0.001") @Digits(integer = 9, fraction = 3) BigDecimal quantity,
	@NotNull @Min(1) @Max(10) Short unitId, Short storageMethodId,
	@PositiveOrZero Integer amount, LocalDate expiresOn) {
}
