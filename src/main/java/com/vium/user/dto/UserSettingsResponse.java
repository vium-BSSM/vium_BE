package com.vium.user.dto;

public record UserSettingsResponse(int expiryAlertDays, boolean autoDisposeEnabled,
		boolean shoppingReminderEnabled, boolean pushEnabled) {
}
