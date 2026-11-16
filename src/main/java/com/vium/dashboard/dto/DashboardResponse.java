package com.vium.dashboard.dto;

import java.time.LocalDate;

public record DashboardResponse(
	ThisMonthDto thisMonth,
	Long totalSavedAmount,
	int savingStreakDays,
	LocalDate lastDisposedOn,
	int activeIngredientCount,
	int expiringSoonCount
) {
}
