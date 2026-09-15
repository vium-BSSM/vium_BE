package com.vium.dispose.presentation.dto;

import java.time.LocalDate;

public record WasteReportDto(
	Long wasteReportId,
	LocalDate periodStart,
	LocalDate periodEnd,
	Long totalWastedAmount,
	Long totalSavedAmount
) {
}
