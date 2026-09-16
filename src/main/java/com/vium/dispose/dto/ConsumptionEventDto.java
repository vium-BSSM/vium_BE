package com.vium.dispose.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ConsumptionEventDto(
	@JsonProperty("eventId")
	Long eventId,
	@JsonProperty("inventoryItemId")
	Long inventoryItemId,
	@JsonProperty("eventType")
	String eventType,
	@JsonProperty("eventSource")
	String eventSource,
	@JsonProperty("quantity")
	BigDecimal quantity,
	@JsonProperty("amount")
	Long amount,
	@JsonProperty("occurredAt")
	LocalDateTime occurredAt
) {
}
