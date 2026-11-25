package com.vium.inventory.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ConsumptionCheckCandidate(
	Long inventoryItemId,
	String name,
	BigDecimal remainingQuantity,
	String unit,
	LocalDate expiresOn
) {
}
