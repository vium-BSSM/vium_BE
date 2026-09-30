package com.vium.recipe.dto;

import java.util.List;

public record RecipeDetailResponse(
	Long recipeId,
	String title,
	String category,
	Integer cookTime,
	String imageUrl,
	String imageAuthorName,
	String imageAuthorUrl,
	List<IngredientResponse> ingredients,
	List<StepResponse> steps
) {
	public record IngredientResponse(
		Long ingredientCatalogId,
		Long inventoryId,  // null 가능
		String name
	) {
	}

	public record StepResponse(
		Integer step,
		String description
	) {
	}
}
