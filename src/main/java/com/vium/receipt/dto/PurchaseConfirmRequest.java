package com.vium.receipt.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

public record PurchaseConfirmRequest(Short purchaseSourceId, @NotNull OffsetDateTime purchasedAt,
	@NotNull @Size(min = 1, max = 100) List<@NotNull @Valid PurchaseConfirmItem> items) {
}
