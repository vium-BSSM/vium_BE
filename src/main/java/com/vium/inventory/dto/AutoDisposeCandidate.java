package com.vium.inventory.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record AutoDisposeCandidate(
	Long inventoryItemId,
	Long userId,
	BigDecimal remainingQuantity,
	BigDecimal initialQuantity,
	BigDecimal amount,
	LocalDate expiresOn
) {
}
