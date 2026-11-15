package com.vium.notification.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record NotificationListResponse(
	@JsonProperty("notifications")
	List<NotificationDto> notifications
) {
}
