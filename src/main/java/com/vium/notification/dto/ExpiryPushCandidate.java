package com.vium.notification.dto;

import java.time.LocalDate;

public record ExpiryPushCandidate(
	Long userId, Long inventoryItemId, String ingredientName, LocalDate expiresOn, int expiryAlertDays,
	Long existingNotificationId
) {
}
