package com.vium.recipe.controller;

import com.vium.global.common.ApiResponse;
import com.vium.global.security.CurrentUserProvider;
import com.vium.recipe.dto.RecipeCompleteRequest;
import com.vium.recipe.dto.RecipeDetailResponse;
import com.vium.recipe.dto.RecommendedRecipesResponse;
import com.vium.recipe.service.RecipeCompletionService;
import com.vium.recipe.service.RecipeDetailEnrichmentService;
import com.vium.recipe.service.RecipeQueryService;
import com.vium.recipe.service.RecipeRecommendationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me/recipes")
@RequiredArgsConstructor
public class RecipeController {

	private final RecipeQueryService recipeQueryService;
	private final RecipeDetailEnrichmentService recipeDetailEnrichmentService;
	private final RecipeCompletionService recipeCompletionService;
	private final RecipeRecommendationService recipeRecommendationService;
	private final CurrentUserProvider currentUserProvider;

	/**
	 * API ② 레시피 상세 조회
	 * GET /api/me/recipes/{recipeId}
	 *
	 * 상세(재료 분량 + 6~12단계)가 아직 없으면(BASIC) 이 열람 요청이 최초로 LLM 보강을 트리거한다.
	 * 소유권 확인은 첫 getRecipeDetail 호출에서 끝나므로, 보강은 그 이후에만 시도한다.
	 */
	@GetMapping("/{recipeId}")
	public ApiResponse<RecipeDetailResponse> getRecipeDetail(@PathVariable Long recipeId) {
		Long userId = currentUserProvider.getCurrentUserId();
		RecipeDetailResponse response = recipeQueryService.getRecipeDetail(userId, recipeId);
		if (recipeDetailEnrichmentService.enrichIfNeeded(recipeId)) {
			response = recipeQueryService.getRecipeDetail(userId, recipeId);
		}
		return ApiResponse.ok(response);
	}

	/**
	 * API ① 맞춤 레시피 목록 (AI 추천)
	 * GET /api/me/recipes/recommended?category=ALL
	 */
	@GetMapping("/recommended")
	public ApiResponse<RecommendedRecipesResponse> getRecommendedRecipes(
		@RequestParam(defaultValue = "ALL") String category
	) {
		Long userId = currentUserProvider.getCurrentUserId();
		RecommendedRecipesResponse response = recipeRecommendationService.getRecommendedRecipes(userId, category);
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
