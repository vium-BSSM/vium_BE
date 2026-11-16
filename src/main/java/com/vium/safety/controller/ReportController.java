package com.vium.safety.controller;

import com.vium.global.common.ApiResponse;
import com.vium.global.security.CurrentUserProvider;
import com.vium.safety.dto.CreateReportRequest;
import com.vium.safety.dto.CreateReportResponse;
import com.vium.safety.service.UserReportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me/reports")
@RequiredArgsConstructor
public class ReportController {

	private final UserReportService userReportService;
	private final CurrentUserProvider currentUserProvider;

	@PostMapping
	public ApiResponse<CreateReportResponse> createReport(@Valid @RequestBody CreateReportRequest request) {
		Long reportId = userReportService.createReport(currentUserProvider.getCurrentUserId(),
			request.targetType(), request.targetId(), request.reason(), request.detail());
		return ApiResponse.ok(new CreateReportResponse(reportId));
	}
}
