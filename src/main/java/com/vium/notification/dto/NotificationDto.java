package com.vium.notification.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record NotificationDto(
	@JsonProperty("notificationId")
	Long notificationId,
	@JsonProperty("type")
	String type,
	@JsonProperty("title")
	String title,
	@JsonProperty("body")
	String body,
	@JsonProperty("scheduledAt")
	LocalDateTime scheduledAt,
	@JsonProperty("sentAt")
	LocalDateTime sentAt,
	@JsonProperty("readAt")
	LocalDateTime readAt
) {
}
