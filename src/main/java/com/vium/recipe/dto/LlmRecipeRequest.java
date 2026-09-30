package com.vium.recipe.dto;

import java.util.List;
import java.util.Map;

public record LlmRecipeRequest(
	List<IngredientInfo> ingredients,
	Map<String, Integer> need,
	List<String> excludeTitles
) {
	public record IngredientInfo(
		Long catalogId,
		String name,
		Integer expireInDays
	) {
	}
}
