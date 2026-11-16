package com.vium.report.controller;

import com.vium.global.common.ApiResponse;
import com.vium.global.security.CurrentUserProvider;
import com.vium.report.config.AiReportProperties;
import com.vium.report.dto.AiWasteReportResponse;
import com.vium.report.dto.CreateAiWasteReportRequest;
import com.vium.report.dto.CreateAiWasteReportResponse;
import com.vium.report.dto.GetAiWasteReportsResponse;
import com.vium.report.service.AiWasteReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@EnableConfigurationProperties(AiReportProperties.class)
public class AiWasteReportController {

	private final AiWasteReportService aiWasteReportService;
	private final CurrentUserProvider currentUserProvider;

	@PostMapping("/api/me/ai-waste-reports")
	public ResponseEntity<ApiResponse<CreateAiWasteReportResponse>> create(
			@RequestBody CreateAiWasteReportRequest request) {
		Long userId = currentUserProvider.getCurrentUserId();
		return ResponseEntity.ok(ApiResponse.ok(aiWasteReportService.create(userId, request)));
	}

	@GetMapping("/api/me/ai-waste-reports")
	public ResponseEntity<ApiResponse<GetAiWasteReportsResponse>> getReports() {
		Long userId = currentUserProvider.getCurrentUserId();
		return ResponseEntity.ok(ApiResponse.ok(aiWasteReportService.getReports(userId)));
	}

	@GetMapping("/api/me/ai-waste-reports/{aiReportId}")
	public ResponseEntity<ApiResponse<AiWasteReportResponse>> getReport(@PathVariable Long aiReportId) {
		Long userId = currentUserProvider.getCurrentUserId();
		return ResponseEntity.ok(ApiResponse.ok(aiWasteReportService.getReport(userId, aiReportId)));
	}
}
