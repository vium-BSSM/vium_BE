package com.vium.dispose.dto;

import java.time.LocalDate;

public record WasteReportDto(
	Long wasteReportId,
	LocalDate periodStart,
	LocalDate periodEnd,
	Long totalWastedAmount,
	Long totalSavedAmount
) {
}
