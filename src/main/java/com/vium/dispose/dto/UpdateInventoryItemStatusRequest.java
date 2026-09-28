package com.vium.dispose.dto;

import java.math.BigDecimal;

public record UpdateInventoryItemStatusRequest(
	String status,
	BigDecimal quantity,
	BigDecimal wasteQuantity,
	Integer wasteAmount
) {
}
