package com.vium.notification.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ConsumptionCheckResponse(List<Item> items) {

	public record Item(Long inventoryItemId, String name, BigDecimal remainingQuantity, String unit,
			LocalDate expiresOn) {
	}
}
