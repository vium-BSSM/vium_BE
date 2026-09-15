package com.vium.dispose.presentation.dto;

import java.util.List;

public record GetWasteReportsResponse(
	List<WasteReportDto> reports
) {
}
