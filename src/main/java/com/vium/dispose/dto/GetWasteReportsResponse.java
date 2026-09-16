package com.vium.dispose.dto;

import java.util.List;

public record GetWasteReportsResponse(
	List<WasteReportDto> reports
) {
}
