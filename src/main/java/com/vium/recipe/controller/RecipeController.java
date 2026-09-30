package com.vium.recipe.controller;

import com.vium.global.common.ApiResponse;
import com.vium.global.security.CurrentUserProvider;
import com.vium.recipe.dto.RecipeDetailResponse;
import com.vium.recipe.service.RecipeQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me/recipes")
@RequiredArgsConstructor
public class RecipeController {

	private final RecipeQueryService recipeQueryService;
	private final CurrentUserProvider currentUserProvider;

	/**
	 * API ② 레시피 상세 조회
	 * GET /api/me/recipes/{recipeId}
	 */
	@GetMapping("/{recipeId}")
	public ApiResponse<RecipeDetailResponse> getRecipeDetail(@PathVariable Long recipeId) {
		Long userId = currentUserProvider.getCurrentUserId();
		RecipeDetailResponse response = recipeQueryService.getRecipeDetail(userId, recipeId);
		return ApiResponse.ok(response);
	}
}
