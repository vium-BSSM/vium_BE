package com.vium.inventory.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TradeTransferSnapshot(Long inventoryItemId, Long ingredientCatalogId, String customName,
	Short unitId, Short storageMethodId, BigDecimal remainingQuantity, BigDecimal initialQuantity,
	BigDecimal amount, LocalDate expiresOn) {}
