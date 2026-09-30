package com.vium.recipe.dto;

import java.util.List;

public record LlmRecipeResponse(
	List<RecipeDto> recipes
) {
	public record RecipeDto(
		String title,
		String category,
		Integer cookTime,
		List<Integer> ingredientCatalogIds,
		List<String> steps,
		String imageKeyword,
		String reason
	) {
	}
}
