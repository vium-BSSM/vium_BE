package com.vium.dispose.presentation.dto;

import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record UpdateInventoryItemStatusResponse(
	Long inventoryItemId,
	String statusCode,
	BigDecimal remainingQuantity,
	BigDecimal wasteQuantity,
	Integer wasteAmount
) {
}
