package com.vium.recipe.dto;

import java.math.BigDecimal;
import java.util.List;

public record RecipeDetailResponse(
	Long recipeId,
	String title,
	String category,
	Integer cookTime,
	String imageUrl,
	Boolean detailReady,  // true면 DETAILED(분량·양념·6~12단계 완료). false면 아직 기본 생성분
	List<IngredientResponse> ingredients,
	List<SeasoningResponse> seasonings,  // detailReady가 false면 빈 배열
	List<StepResponse> steps
) {
	public record IngredientResponse(
		Long ingredientCatalogId,
		Long inventoryId,  // null 가능
		String name,
		BigDecimal remainingQuantity,  // inventoryId가 null이면 null
		String unit,  // inventoryId가 null이면 null. GET /api/me/ingredients의 unit과 같은 값(units.name)
		String amount  // 이 레시피에서 쓰는 분량(자유 텍스트, 예: "1/2모"). detailReady가 false면 null
	) {
	}

	public record SeasoningResponse(
		String name,
		String amount
	) {
	}

	public record StepResponse(
		Integer step,
		String description
	) {
	}
}
