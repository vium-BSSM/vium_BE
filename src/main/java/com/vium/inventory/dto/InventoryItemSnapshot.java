package com.vium.inventory.dto;

import java.math.BigDecimal;

public record InventoryItemSnapshot(
	Long inventoryItemId,
	Long userId,
	BigDecimal remainingQuantity,
	BigDecimal initialQuantity,
	BigDecimal amount
) {
}
