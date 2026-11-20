package com.vium.notification.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ExpiryNotificationResponse(
	@JsonProperty("items")
	List<Item> items
) {

	public record Item(
		@JsonProperty("inventoryItemId")
		Long inventoryItemId,
		@JsonProperty("name")
		String name,
		@JsonProperty("daysLeft")
		int daysLeft
	) {
	}
}
