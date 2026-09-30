package com.vium.recipe.controller;

import com.vium.global.common.ApiResponse;
import com.vium.global.security.CurrentUserProvider;
import com.vium.recipe.dto.RecipeCompleteRequest;
import com.vium.recipe.dto.RecipeDetailResponse;
import com.vium.recipe.service.RecipeCompletionService;
import com.vium.recipe.service.RecipeQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me/recipes")
@RequiredArgsConstructor
public class RecipeController {

	private final RecipeQueryService recipeQueryService;
	private final RecipeCompletionService recipeCompletionService;
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

	/**
	 * API ④ 요리 완료 · 재료 사용량 반영
	 * POST /api/me/recipes/{recipeId}/complete
	 */
	@PostMapping("/{recipeId}/complete")
	public ApiResponse<Void> completeRecipe(
		@PathVariable Long recipeId,
		@Valid @RequestBody RecipeCompleteRequest request
	) {
		Long userId = currentUserProvider.getCurrentUserId();
		recipeCompletionService.completeRecipe(userId, recipeId, request);
		return ApiResponse.ok(null);
	}
}
