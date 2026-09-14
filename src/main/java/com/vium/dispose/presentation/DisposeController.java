package com.vium.dispose.presentation;

import com.vium.dispose.application.UpdateInventoryItemStatusUseCase;
import com.vium.dispose.presentation.dto.UpdateInventoryItemStatusRequest;
import com.vium.dispose.presentation.dto.UpdateInventoryItemStatusResponse;
import com.vium.global.auth.AuthenticationUtil;
import com.vium.global.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class DisposeController {

	private final UpdateInventoryItemStatusUseCase updateInventoryItemStatusUseCase;

	@PatchMapping("/api/me/ingredients/{ingredientId}/status")
	public ResponseEntity<ApiResponse<UpdateInventoryItemStatusResponse>> updateStatus(
			@PathVariable Long ingredientId,
			@RequestBody UpdateInventoryItemStatusRequest request) {
		Long userId = AuthenticationUtil.getCurrentUserId();
		UpdateInventoryItemStatusResponse response = updateInventoryItemStatusUseCase.execute(userId,
				ingredientId, request);
		return ResponseEntity.ok(ApiResponse.ok(response));
	}
}
