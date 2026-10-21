package com.vium.recipe.dto;

import java.math.BigDecimal;
import java.util.List;

public record RecipeDetailResponse(
	Long recipeId,
	String title,
	String category,
	Integer cookTime,
	String imageUrl,
	List<IngredientResponse> ingredients,
	List<StepResponse> steps
) {
	public record IngredientResponse(
		Long ingredientCatalogId,
		Long inventoryId,  // null 가능
		String name,
		BigDecimal remainingQuantity,  // inventoryId가 null이면 null
		String unit  // inventoryId가 null이면 null. GET /api/me/ingredients의 unit과 같은 값(units.name)
	) {
	}

	public record StepResponse(
		Integer step,
		String description
	) {
	}
}
