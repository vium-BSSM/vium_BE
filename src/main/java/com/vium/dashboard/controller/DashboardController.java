package com.vium.dashboard.controller;

import com.vium.dashboard.dto.DashboardResponse;
import com.vium.dashboard.service.DashboardService;
import com.vium.global.common.ApiResponse;
import com.vium.global.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class DashboardController {

	private final DashboardService dashboardService;
	private final CurrentUserProvider currentUserProvider;

	@GetMapping("/api/me/dashboard")
	public ResponseEntity<ApiResponse<DashboardResponse>> getDashboard() {
		Long userId = currentUserProvider.getCurrentUserId();
		DashboardResponse response = dashboardService.getDashboard(userId);
		return ResponseEntity.ok(ApiResponse.ok(response));
	}
}
