package com.vium.dashboard.dto;

public record ThisMonthDto(
	String yearMonth,
	Long savedAmount,
	int consumedCount,
	int sharedCount,
	Long wastedAmount,
	int wastedCount
) {
}
