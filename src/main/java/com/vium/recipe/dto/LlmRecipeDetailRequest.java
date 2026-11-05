package com.vium.recipe.dto;

import java.util.List;

public record LlmRecipeDetailRequest(
	Long recipeId,  // 프롬프트에는 쓰이지 않음. 로그 상관관계용
	String title,
	String category,
	List<IngredientInfo> ingredients
) {
	public record IngredientInfo(
		Long catalogId,
		String name
	) {
	}
}
