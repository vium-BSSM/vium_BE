package com.vium.notification.dto;

public record ConsumeCheckPushCandidate(
	Long userId,
	Long inventoryItemId,
	String ingredientName,
	Long existingNotificationId
) {
}
