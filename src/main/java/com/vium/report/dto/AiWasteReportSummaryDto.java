package com.vium.report.dto;

import java.time.OffsetDateTime;

public record AiWasteReportSummaryDto(Long aiReportId, String yearMonth, OffsetDateTime createdAt) {
}
