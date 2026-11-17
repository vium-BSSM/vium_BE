package com.vium.report.dto;

import java.util.List;

public record LlmReportResponse(String summary, List<InsightDto> insights, List<String> suggestions) {
}
