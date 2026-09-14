package com.vium.dispose.presentation.dto;

import java.math.BigDecimal;

public record UpdateInventoryItemStatusRequest(
	String status,
	BigDecimal quantity,
	BigDecimal wasteQuantity,
	Integer wasteAmount
) {
}
