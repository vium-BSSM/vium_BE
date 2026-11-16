package com.vium.report.dto;

import java.util.List;

public record GetAiWasteReportsResponse(List<AiWasteReportSummaryDto> reports) {
}
