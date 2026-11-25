package com.vium.notification.dto;

public record ShoppingReminderPushCandidate(
	Long userId,
	long activeItemCount,
	boolean pushEnabled,
	Long existingNotificationId
) {
}
