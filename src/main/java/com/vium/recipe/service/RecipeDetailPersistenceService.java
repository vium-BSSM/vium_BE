package com.vium.recipe.service;

import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.recipe.dto.LlmRecipeDetailResponse;
import com.vium.recipe.dto.LlmRecipeDetailResponse.IngredientAmount;
import com.vium.recipe.dto.LlmRecipeDetailResponse.SeasoningItem;
import com.vium.recipe.entity.Recipe;
import com.vium.recipe.entity.RecipeIngredient;
import com.vium.recipe.entity.RecipeSeasoning;
import com.vium.recipe.entity.RecipeStep;
import com.vium.recipe.repository.RecipeIngredientRepository;
import com.vium.recipe.repository.RecipeRepository;
import com.vium.recipe.repository.RecipeSeasoningRepository;
import com.vium.recipe.repository.RecipeStepRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 레시피 상세 보강(분량+양념+steps) 저장을 전담한다.
 * LLM 호출(느린 네트워크 작업)은 이 서비스 밖에서 이뤄지며, 여기선 짧은 쓰기 트랜잭션만 수행한다.
 */
@Service
@RequiredArgsConstructor
public class RecipeDetailPersistenceService {

	private final RecipeRepository recipeRepository;
	private final RecipeIngredientRepository recipeIngredientRepository;
	private final RecipeStepRepository recipeStepRepository;
	private final RecipeSeasoningRepository recipeSeasoningRepository;

	@Transactional
	public void applyEnrichment(Long recipeId, LlmRecipeDetailResponse response) {
		Map<Long, String> amountByCatalogId = response.ingredients().stream()
			.collect(Collectors.toMap(IngredientAmount::catalogId, IngredientAmount::amount));

		List<RecipeIngredient> ingredients = recipeIngredientRepository.findByRecipeIdOrderByIdAsc(recipeId);
		for (RecipeIngredient ingredient : ingredients) {
			ingredient.setAmount(amountByCatalogId.get(ingredient.getIngredientCatalogId()));
		}
		recipeIngredientRepository.saveAll(ingredients);

		// recipe_steps/recipe_seasonings의 (recipe_id, 순서) 유니크 제약과 겹치지 않도록,
		// 삭제를 먼저 flush한 뒤 새로 삽입한다.
		recipeStepRepository.deleteByRecipeId(recipeId);
		recipeSeasoningRepository.deleteByRecipeId(recipeId);
		recipeStepRepository.flush();
		recipeSeasoningRepository.flush();

		List<RecipeStep> steps = new ArrayList<>();
		for (int i = 0; i < response.steps().size(); i++) {
			steps.add(RecipeStep.builder()
				.recipeId(recipeId)
				.stepOrder(i + 1)
				.description(response.steps().get(i))
				.build());
		}
		recipeStepRepository.saveAll(steps);

		List<RecipeSeasoning> seasonings = new ArrayList<>();
		List<SeasoningItem> seasoningItems = response.seasonings();
		for (int i = 0; i < seasoningItems.size(); i++) {
			SeasoningItem item = seasoningItems.get(i);
			seasonings.add(RecipeSeasoning.builder()
				.recipeId(recipeId)
				.name(item.name())
				.amount(item.amount())
				.sortOrder(i + 1)
				.build());
		}
		recipeSeasoningRepository.saveAll(seasonings);

		Recipe recipe = recipeRepository.findById(recipeId)
			.orElseThrow(() -> new BusinessException(ErrorCode.RECIPE_NOT_FOUND));
		recipe.markDetailGenerated();
		recipeRepository.save(recipe);
	}

	@Transactional
	public void resetToBasic(Long recipeId) {
		recipeRepository.findById(recipeId).ifPresent(recipe -> {
			recipe.resetDetailGeneration();
			recipeRepository.save(recipe);
		});
	}
}
