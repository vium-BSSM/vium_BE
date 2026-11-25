package com.vium.notification.dto;

import java.time.LocalDate;

public record ExpiryPushCandidate(
	Long userId, Long inventoryItemId, String ingredientName, LocalDate expiresOn, int expiryAlertDays,
	boolean pushEnabled,
	Long existingNotificationId
) {
}
