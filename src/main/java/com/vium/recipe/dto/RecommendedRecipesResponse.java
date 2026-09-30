package com.vium.recipe.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record RecommendedRecipesResponse(
	@JsonProperty("recipes")
	List<RecipeCard> recipes
) {

	public record RecipeCard(
		@JsonProperty("recipeId")
		Long recipeId,
		@JsonProperty("title")
		String title,
		@JsonProperty("category")
		String category,
		@JsonProperty("cookTime")
		Integer cookTime,
		@JsonProperty("usedIngredients")
		List<IngredientInfo> usedIngredients
	) {

		public record IngredientInfo(
			@JsonProperty("ingredientCatalogId")
			Long ingredientCatalogId,
			@JsonProperty("name")
			String name
		) {}
	}
}
