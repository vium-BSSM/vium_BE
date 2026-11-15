package com.vium.notification.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record NotificationReadResponse(
	@JsonProperty("notificationId")
	Long notificationId,
	@JsonProperty("readAt")
	LocalDateTime readAt
) {
}
