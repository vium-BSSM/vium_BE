package com.vium.safety.dto;

import com.vium.safety.entity.ReportReason;
import com.vium.safety.entity.ReportTargetType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateReportRequest(
		@NotNull ReportTargetType targetType,
		@NotNull @Positive Long targetId,
		@NotNull ReportReason reason,
		@Size(max = 500) String detail) {
}
