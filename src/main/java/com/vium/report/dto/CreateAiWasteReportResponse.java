package com.vium.report.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record CreateAiWasteReportResponse(
	Long aiReportId,
	String yearMonth,
	Integer cost,
	String summary,
	List<InsightDto> insights,
	List<String> suggestions,
	OffsetDateTime createdAt,
	Integer chargedCredit) {
}
