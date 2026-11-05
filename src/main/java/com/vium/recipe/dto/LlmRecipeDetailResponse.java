package com.vium.recipe.dto;

import java.util.List;

public record LlmRecipeDetailResponse(
	List<IngredientAmount> ingredients,
	List<SeasoningItem> seasonings,
	List<String> steps
) {
	public record IngredientAmount(
		Long catalogId,
		String amount
	) {
	}

	public record SeasoningItem(
		String name,
		String amount
	) {
	}
}
