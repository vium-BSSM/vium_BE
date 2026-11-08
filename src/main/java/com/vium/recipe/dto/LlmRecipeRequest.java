package com.vium.recipe.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record LlmRecipeRequest(
	UUID batchId,  // 프롬프트에는 쓰이지 않음. 로그 상관관계용
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
