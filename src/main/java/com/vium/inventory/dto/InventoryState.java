package com.vium.inventory.dto;

import java.math.BigDecimal;

public record InventoryState(Long inventoryItemId, Long userId, BigDecimal remainingQuantity) {
}
