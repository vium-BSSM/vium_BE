package com.vium.dispose.controller;

import com.vium.dispose.dto.UpdateInventoryItemStatusRequest;
import com.vium.dispose.dto.UpdateInventoryItemStatusResponse;
import com.vium.dispose.dto.GetWasteReportsResponse;
import com.vium.dispose.dto.GetWastePatternsResponse;
import com.vium.dispose.dto.GetConsumptionEventsResponse;
import com.vium.dispose.service.DisposeService;
import com.vium.dispose.service.WasteReportService;
import com.vium.global.common.ApiResponse;
import com.vium.global.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;

@RestController
@RequiredArgsConstructor
public class DisposeController {

	private final DisposeService disposeService;
	private final WasteReportService wasteReportService;
	private final CurrentUserProvider currentUserProvider;

	@PatchMapping("/api/me/ingredients/{ingredientId}/status")
	public ResponseEntity<ApiResponse<UpdateInventoryItemStatusResponse>> updateStatus(
			@PathVariable Long ingredientId,
			@RequestBody UpdateInventoryItemStatusRequest request) {
		Long userId = currentUserProvider.getCurrentUserId();
		UpdateInventoryItemStatusResponse response = disposeService.updateStatus(userId,
				ingredientId, request);
		return ResponseEntity.ok(ApiResponse.ok(response));
	}

	@GetMapping("/api/me/waste-reports")
	public ResponseEntity<ApiResponse<GetWasteReportsResponse>> getWasteReports() {
		Long userId = currentUserProvider.getCurrentUserId();
		GetWasteReportsResponse response = wasteReportService.getReports(userId);
		return ResponseEntity.ok(ApiResponse.ok(response));
	}

	@GetMapping("/api/me/waste-patterns")
	public ResponseEntity<ApiResponse<GetWastePatternsResponse>> getWastePatterns() {
		Long userId = currentUserProvider.getCurrentUserId();
		GetWastePatternsResponse response = wasteReportService.getPatterns(userId);
		return ResponseEntity.ok(ApiResponse.ok(response));
	}

	@GetMapping("/api/me/consumption-events")
	public ResponseEntity<ApiResponse<GetConsumptionEventsResponse>> getConsumptionEvents(
			@RequestParam(required = false) LocalDate from,
			@RequestParam(required = false) LocalDate to,
			@RequestParam(required = false) String eventType) {
		Long userId = currentUserProvider.getCurrentUserId();
		GetConsumptionEventsResponse response = disposeService.getEvents(userId, from, to, eventType);
		return ResponseEntity.ok(ApiResponse.ok(response));
	}
}
