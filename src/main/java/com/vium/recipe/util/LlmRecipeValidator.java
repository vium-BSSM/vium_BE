package com.vium.recipe.util;

import com.vium.recipe.dto.LlmRecipeResponse.RecipeDto;
import com.vium.recipe.entity.RecipeCategory;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class LlmRecipeValidator {

	/**
	 * LLM이 생성한 레시피를 검증합니다.
	 * 규칙을 어긴 레시피는 통째로 버립니다.
	 *
	 * @param recipes LLM 응답의 레시피 목록
	 * @param userIngredientCatalogIds 사용자가 보유한 재료 카탈로그 ID
	 * @param need 카테고리별 필요 개수
	 * @return 검증을 통과한 레시피 목록 (카테고리별 need개 제한, reason은 200자로 자름)
	 */
	public List<RecipeDto> validate(List<RecipeDto> recipes, List<Long> userIngredientCatalogIds,
		Map<String, Integer> need) {
		Set<Long> validCatalogIds = new HashSet<>(userIngredientCatalogIds);
		Set<String> validCategories = need.keySet();

		Map<String, List<RecipeDto>> byCategory = recipes.stream()
			.filter(recipe -> validateRecipe(recipe, validCatalogIds, validCategories))
			.map(this::trimReasonTo200Chars)
			.collect(Collectors.groupingBy(RecipeDto::category));

		// 카테고리별 need개만 선택
		return byCategory.entrySet().stream()
			.flatMap(entry -> entry.getValue().stream()
				.limit(need.getOrDefault(entry.getKey(), 0)))
			.collect(Collectors.toList());
	}

	private RecipeDto trimReasonTo200Chars(RecipeDto recipe) {
		if (recipe.reason() == null || recipe.reason().length() <= 200) {
			return recipe;
		}
		return new RecipeDto(
			recipe.title(),
			recipe.category(),
			recipe.cookTime(),
			recipe.ingredientCatalogIds(),
			recipe.steps(),
			recipe.imageKeyword(),
			recipe.reason().substring(0, 200)
		);
	}

	private boolean validateRecipe(RecipeDto recipe, Set<Long> validCatalogIds,
		Set<String> validCategories) {
		try {
			// title 검증: 공백 아님, 1~100자
			if (recipe.title() == null || recipe.title().isBlank() || recipe.title().length() > 100) {
				log.debug("레시피 제목 검증 실패: '{}'", recipe.title());
				return false;
			}

			// category 검증: need에 있는 카테고리만
			if (recipe.category() == null || !validCategories.contains(recipe.category())) {
				log.debug("레시피 카테고리 검증 실패: '{}'", recipe.category());
				return false;
			}

			// cookTime 검증: 정수 1~180
			if (recipe.cookTime() == null || recipe.cookTime() < 1 || recipe.cookTime() > 180) {
				log.debug("조리시간 검증 실패: {}", recipe.cookTime());
				return false;
			}

			// ingredientCatalogIds 검증
			if (recipe.ingredientCatalogIds() == null || recipe.ingredientCatalogIds().isEmpty()) {
				log.debug("재료 목록 검증 실패: 비어있음");
				return false;
			}

			Set<Integer> uniqueIds = new HashSet<>(recipe.ingredientCatalogIds());
			if (uniqueIds.size() != recipe.ingredientCatalogIds().size()) {
				log.debug("재료 ID 중복 발견");
				// 중복 제거
			}

			for (Integer catalogId : uniqueIds) {
				if (!validCatalogIds.contains(catalogId.longValue())) {
					log.debug("재료 '{}' LLM 입력 목록에 없음", catalogId);
					return false;
				}
			}

			// steps 검증: 1~10개, 각 항목 공백 아님, 500자 이하
			if (recipe.steps() == null || recipe.steps().isEmpty() || recipe.steps().size() > 10) {
				log.debug("조리 단계 개수 검증 실패: {}", recipe.steps() != null ? recipe.steps().size() : "null");
				return false;
			}

			for (String step : recipe.steps()) {
				if (step == null || step.isBlank() || step.length() > 500) {
					log.debug("조리 단계 텍스트 검증 실패: '{}'", step);
					return false;
				}
			}

			// imageKeyword 검증: 없거나 비어있으면 대체 검색어 사용 (레시피는 버리지 않음)
			// (검증 완료 후 서비스에서 처리)

			// reason 검증: 255자 초과 시 자름
			// (검증 완료 후 서비스에서 처리)

			return true;
		} catch (Exception e) {
			log.error("레시피 검증 중 오류 발생", e);
			return false;
		}
	}
}
