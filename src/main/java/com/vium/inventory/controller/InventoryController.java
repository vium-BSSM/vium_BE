package com.vium.inventory.controller;

import com.vium.global.auth.CurrentUserProvider;
import com.vium.global.common.ApiResponse;
import com.vium.inventory.dto.IngredientRegisterRequest;
import com.vium.inventory.dto.IngredientResponse;
import com.vium.inventory.service.InventoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me/ingredients")
@RequiredArgsConstructor
public class InventoryController {

	private final InventoryService inventoryService;
	private final CurrentUserProvider currentUserProvider;

	@PostMapping
	public ApiResponse<IngredientResponse> register(@Valid @RequestBody IngredientRegisterRequest request) {
		Long userId = currentUserProvider.getCurrentUserId();
		return ApiResponse.ok(inventoryService.register(userId, request));
	}
}
