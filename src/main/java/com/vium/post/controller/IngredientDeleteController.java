package com.vium.post.controller;

import com.vium.global.common.ApiResponse;
import com.vium.global.security.CurrentUserProvider;
import com.vium.post.service.IngredientDeleteService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me/ingredients")
@RequiredArgsConstructor
public class IngredientDeleteController {

	private final IngredientDeleteService ingredientDeleteService;
	private final CurrentUserProvider currentUserProvider;

	@DeleteMapping("/{ingredientId}")
	public ApiResponse<Void> delete(@PathVariable Long ingredientId) {
		Long userId = currentUserProvider.getCurrentUserId();
		ingredientDeleteService.delete(userId, ingredientId);
		return ApiResponse.ok();
	}
}
