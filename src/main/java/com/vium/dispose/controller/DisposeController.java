package com.vium.dispose.controller;

import com.vium.dispose.dto.UpdateInventoryItemStatusRequest;
import com.vium.dispose.dto.UpdateInventoryItemStatusResponse;
import com.vium.dispose.service.DisposeService;
import com.vium.global.common.ApiResponse;
import com.vium.global.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class DisposeController {

	private final DisposeService disposeService;
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
}
