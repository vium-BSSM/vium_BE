package com.vium.dispose.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record UpdateInventoryItemStatusResponse(
	Long inventoryItemId,
	String statusCode,
	BigDecimal remainingQuantity,
	BigDecimal wasteQuantity,
	Integer wasteAmount
) {
}
