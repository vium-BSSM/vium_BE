package com.vium.dispose.dto;

import java.time.LocalDate;
import java.util.List;

public record WasteReportDto(
	Long wasteReportId,
	LocalDate periodStart,
	LocalDate periodEnd,
	Long totalWastedAmount,
	Long totalSavedAmount,
	int registeredCount,
	int consumedCount,
	int expiredCount,
	List<TopWastedItemDto> topWastedItems
) {

	public WasteReportDto(Long wasteReportId, LocalDate periodStart, LocalDate periodEnd,
		Long totalWastedAmount, Long totalSavedAmount) {
		this(wasteReportId, periodStart, periodEnd, totalWastedAmount, totalSavedAmount, 0, 0, 0, List.of());
	}

	public WasteReportDto withSummary(int registeredCount, int consumedCount, int expiredCount,
		List<TopWastedItemDto> topWastedItems) {
		return new WasteReportDto(wasteReportId, periodStart, periodEnd, totalWastedAmount, totalSavedAmount,
			registeredCount, consumedCount, expiredCount, topWastedItems);
	}
}
