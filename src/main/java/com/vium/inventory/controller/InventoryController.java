package com.vium.inventory.controller;

import com.vium.global.common.ApiResponse;
import com.vium.global.security.CurrentUserProvider;
import com.vium.inventory.dto.IngredientListResponse;
import com.vium.inventory.dto.IngredientRegisterRequest;
import com.vium.inventory.dto.IngredientResponse;
import com.vium.inventory.dto.IngredientUpdateRequest;
import com.vium.inventory.dto.IngredientUpdateResponse;
import com.vium.inventory.service.InventoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me/ingredients")
@RequiredArgsConstructor
public class InventoryController {

	private final InventoryService inventoryService;
	private final CurrentUserProvider currentUserProvider;

	@PatchMapping("/{ingredientId}")
	public ApiResponse<IngredientUpdateResponse> update(@PathVariable Long ingredientId,
			@Valid @RequestBody IngredientUpdateRequest request) {
		return ApiResponse.ok(inventoryService.update(currentUserProvider.getCurrentUserId(), ingredientId, request));
	}

	@GetMapping
	public ApiResponse<IngredientListResponse> list(
			@RequestParam(defaultValue = "false") boolean expiringSoon) {
		return ApiResponse.ok(inventoryService.list(currentUserProvider.getCurrentUserId(), expiringSoon));
	}

	@PostMapping
	public ApiResponse<IngredientResponse> register(@Valid @RequestBody IngredientRegisterRequest request) {
		Long userId = currentUserProvider.getCurrentUserId();
		return ApiResponse.ok(inventoryService.register(userId, request));
	}
}
