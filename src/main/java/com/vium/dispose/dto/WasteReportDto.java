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
	List<TopWastedItemDto> topWastedItems,
	Long purchaseTotalAmount,
	int sharedCount,
	Long sharedAmount
) {

	public WasteReportDto(Long wasteReportId, LocalDate periodStart, LocalDate periodEnd,
		Long totalWastedAmount, Long totalSavedAmount) {
		this(wasteReportId, periodStart, periodEnd, totalWastedAmount, totalSavedAmount, 0, 0, 0, List.of(),
			0L, 0, 0L);
	}

	public WasteReportDto withSummary(int registeredCount, int consumedCount, int expiredCount,
		List<TopWastedItemDto> topWastedItems) {
		return new WasteReportDto(wasteReportId, periodStart, periodEnd, totalWastedAmount, totalSavedAmount,
			registeredCount, consumedCount, expiredCount, topWastedItems,
			purchaseTotalAmount, sharedCount, sharedAmount);
	}

	public WasteReportDto withExtras(Long purchaseTotalAmount, int sharedCount, Long sharedAmount) {
		return new WasteReportDto(wasteReportId, periodStart, periodEnd, totalWastedAmount, totalSavedAmount,
			registeredCount, consumedCount, expiredCount, topWastedItems,
			purchaseTotalAmount, sharedCount, sharedAmount);
	}
}
