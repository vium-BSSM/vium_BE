package com.vium.user.dto;

public record UserSettingsUpdateRequest(Integer expiryAlertDays, Boolean autoDisposeEnabled,
		Boolean shoppingReminderEnabled, Boolean pushEnabled) {
}
