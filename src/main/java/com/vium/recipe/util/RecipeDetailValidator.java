package com.vium.recipe.util;

import com.vium.recipe.dto.LlmRecipeDetailResponse;
import com.vium.recipe.dto.LlmRecipeDetailResponse.IngredientAmount;
import com.vium.recipe.dto.LlmRecipeDetailResponse.SeasoningItem;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class RecipeDetailValidator {

	private static final int MAX_SEASONINGS = 10;

	/**
	 * LLM이 보강한 레시피 상세(재료 분량 + 양념 + steps)를 검증한다.
	 * 규칙을 어기면 예외를 던져 보강 전체를 버린다(기존 BASIC 상세는 그대로 유지됨).
	 * 단, 양념 이름이 보유 재료 이름과 겹치면 전체를 버리지 않고 해당 양념 항목만 조용히 제거하며,
	 * 양념 개수가 최대치를 넘으면 거부하지 않고 앞에서 MAX_SEASONINGS개만 남긴다.
	 *
	 * @param response LLM 응답
	 * @param expectedCatalogIds 레시피가 원래 가지고 있던 재료 ID 집합(추가/누락 불가)
	 * @param existingIngredientNames 레시피가 이미 가진 재료 이름(양념과의 중복 제거용)
	 * @return 중복 양념이 제거된 응답
	 */
	public LlmRecipeDetailResponse validate(LlmRecipeDetailResponse response, Set<Long> expectedCatalogIds,
		Set<String> existingIngredientNames) {
		if (response.steps() == null || response.steps().size() < 6 || response.steps().size() > 12) {
			throw new IllegalArgumentException("조리 단계 개수 검증 실패: "
				+ (response.steps() != null ? response.steps().size() : "null"));
		}
		for (String step : response.steps()) {
			if (step == null || step.isBlank() || step.length() > 500) {
				throw new IllegalArgumentException("조리 단계 텍스트 검증 실패: '" + step + "'");
			}
		}

		if (response.ingredients() == null) {
			throw new IllegalArgumentException("재료 분량 목록이 없습니다");
		}
		Set<Long> responseCatalogIds = response.ingredients().stream()
			.map(IngredientAmount::catalogId)
			.collect(Collectors.toSet());
		if (!responseCatalogIds.equals(expectedCatalogIds)) {
			throw new IllegalArgumentException("재료 목록이 기존 레시피와 일치하지 않습니다: " + responseCatalogIds);
		}
		for (IngredientAmount ingredient : response.ingredients()) {
			String amount = ingredient.amount();
			if (amount == null || amount.isBlank() || amount.length() > 50) {
				throw new IllegalArgumentException("재료 분량 텍스트 검증 실패: '" + amount + "'");
			}
		}

		List<SeasoningItem> validSeasonings = validateAndFilterSeasonings(response.seasonings(), existingIngredientNames);

		return new LlmRecipeDetailResponse(response.ingredients(), validSeasonings, response.steps());
	}

	private List<SeasoningItem> validateAndFilterSeasonings(List<SeasoningItem> seasonings,
		Set<String> existingIngredientNames) {
		if (seasonings == null) {
			throw new IllegalArgumentException("양념 목록이 없습니다");
		}
		// 개수 초과는 거부하지 않고 앞에서 MAX_SEASONINGS개만 남긴다(나머지는 버림, 길이 검증 대상에서도 제외).
		List<SeasoningItem> truncated = seasonings.size() > MAX_SEASONINGS
			? seasonings.subList(0, MAX_SEASONINGS)
			: seasonings;

		for (SeasoningItem seasoning : truncated) {
			if (seasoning.name() == null || seasoning.name().isBlank() || seasoning.name().length() > 30) {
				throw new IllegalArgumentException("양념 이름 검증 실패: '" + seasoning.name() + "'");
			}
			if (seasoning.amount() == null || seasoning.amount().isBlank() || seasoning.amount().length() > 50) {
				throw new IllegalArgumentException("양념 분량 검증 실패: '" + seasoning.amount() + "'");
			}
		}

		Set<String> normalizedIngredientNames = existingIngredientNames.stream()
			.map(String::strip)
			.collect(Collectors.toSet());

		return truncated.stream()
			.filter(seasoning -> !normalizedIngredientNames.contains(seasoning.name().strip()))
			.toList();
	}
}
