package com.vium.dispose.presentation;

import com.vium.dispose.application.AutoDisposeInventoryItemsUseCase;
import com.vium.dispose.application.GetConsumptionEventsUseCase;
import com.vium.dispose.application.GetWastePatternsUseCase;
import com.vium.dispose.application.GetWasteReportsUseCase;
import com.vium.dispose.application.UpdateInventoryItemStatusUseCase;
import com.vium.dispose.presentation.dto.AutoDisposeResponse;
import com.vium.dispose.presentation.dto.GetConsumptionEventsResponse;
import com.vium.dispose.presentation.dto.GetWastePatternsResponse;
import com.vium.dispose.presentation.dto.GetWasteReportsResponse;
import com.vium.dispose.presentation.dto.UpdateInventoryItemStatusRequest;
import com.vium.dispose.presentation.dto.UpdateInventoryItemStatusResponse;
import com.vium.global.auth.AuthenticationUtil;
import com.vium.global.common.ApiResponse;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class DisposeController {

	private final UpdateInventoryItemStatusUseCase updateInventoryItemStatusUseCase;
	private final AutoDisposeInventoryItemsUseCase autoDisposeInventoryItemsUseCase;
	private final GetConsumptionEventsUseCase getConsumptionEventsUseCase;
	private final GetWastePatternsUseCase getWastePatternsUseCase;
	private final GetWasteReportsUseCase getWasteReportsUseCase;

	@PatchMapping("/api/me/ingredients/{ingredientId}/status")
	public ResponseEntity<ApiResponse<UpdateInventoryItemStatusResponse>> updateStatus(
			@PathVariable Long ingredientId,
			@RequestBody UpdateInventoryItemStatusRequest request) {
		// TODO: AuthenticationUtil 사용자 ID 추출 방식 확인 필요 (§5-2)
		Long userId = AuthenticationUtil.getCurrentUserId();
		UpdateInventoryItemStatusResponse response = updateInventoryItemStatusUseCase.execute(userId,
				ingredientId, request);
		return ResponseEntity.ok(ApiResponse.ok(response));
	}

	@PostMapping("/api/ingredients/auto-dispose")
	public ResponseEntity<ApiResponse<AutoDisposeResponse>> autoDispose() {
		int disposedCount = autoDisposeInventoryItemsUseCase.execute();
		return ResponseEntity.ok(ApiResponse.ok(new AutoDisposeResponse(disposedCount)));
	}

	@GetMapping("/api/me/consumption-events")
	public ResponseEntity<ApiResponse<GetConsumptionEventsResponse>> getConsumptionEvents(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) String eventType) {
		Long userId = AuthenticationUtil.getCurrentUserId();
		GetConsumptionEventsResponse response = getConsumptionEventsUseCase.execute(userId, from, to, eventType);
		return ResponseEntity.ok(ApiResponse.ok(response));
	}

	@GetMapping("/api/me/waste-patterns")
	public ResponseEntity<ApiResponse<GetWastePatternsResponse>> getWastePatterns() {
		Long userId = AuthenticationUtil.getCurrentUserId();
		GetWastePatternsResponse response = getWastePatternsUseCase.execute(userId);
		return ResponseEntity.ok(ApiResponse.ok(response));
	}

	@GetMapping("/api/me/waste-reports")
	public ResponseEntity<ApiResponse<GetWasteReportsResponse>> getWasteReports() {
		Long userId = AuthenticationUtil.getCurrentUserId();
		GetWasteReportsResponse response = getWasteReportsUseCase.execute(userId);
		return ResponseEntity.ok(ApiResponse.ok(response));
	}
}
