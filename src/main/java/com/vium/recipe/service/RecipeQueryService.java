package com.vium.recipe.service;

import com.vium.global.code.ItemStatusRepository;
import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.ingredient.service.IngredientCatalogService;
import com.vium.inventory.entity.InventoryItem;
import com.vium.inventory.repository.InventoryItemRepository;
import com.vium.recipe.dto.RecipeDetailResponse;
import com.vium.recipe.dto.RecipeDetailResponse.IngredientResponse;
import com.vium.recipe.dto.RecipeDetailResponse.StepResponse;
import com.vium.recipe.entity.Recipe;
import com.vium.recipe.entity.RecipeIngredient;
import com.vium.recipe.entity.RecipeStep;
import com.vium.recipe.repository.RecipeIngredientRepository;
import com.vium.recipe.repository.RecipeRepository;
import com.vium.recipe.repository.RecipeSuggestionRepository;
import com.vium.recipe.repository.RecipeStepRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecipeQueryService {

	private final RecipeRepository recipeRepository;
	private final RecipeIngredientRepository recipeIngredientRepository;
	private final RecipeStepRepository recipeStepRepository;
	private final RecipeSuggestionRepository recipeSuggestionRepository;
	private final InventoryItemRepository inventoryItemRepository;
	private final IngredientCatalogService ingredientCatalogService;
	private final ItemStatusRepository itemStatusRepository;

	/**
	 * 레시피 상세 정보를 조회합니다 (API ②).
	 * 사용자가 추천받은 레시피만 조회 가능합니다.
	 *
	 * @param userId 현재 사용자 ID
	 * @param recipeId 레시피 ID
	 * @return 레시피 상세 정보
	 * @throws NotFoundException 레시피 없음 또는 추천받지 않은 레시피
	 */
	public RecipeDetailResponse getRecipeDetail(Long userId, Long recipeId) {
		// 1. 사용자가 추천받은 레시피인지 확인 (3-4의 1)
		recipeSuggestionRepository.findByUserIdAndRecipeId(userId, recipeId)
			.orElseThrow(() -> new BusinessException(ErrorCode.RECIPE_NOT_FOUND, "레시피를 찾을 수 없습니다"));

		// 2. 레시피, 재료, 조리법 조회 (3-4의 2)
		Recipe recipe = recipeRepository.findById(recipeId)
			.orElseThrow(() -> new BusinessException(ErrorCode.RECIPE_NOT_FOUND, "레시피를 찾을 수 없습니다"));

		List<RecipeIngredient> ingredients = recipeIngredientRepository.findByRecipeIdOrderByIdAsc(
			recipeId);
		List<RecipeStep> steps = recipeStepRepository.findByRecipeIdOrderByStepOrderAsc(recipeId);

		// 3. inventoryId 매핑 (3-4의 3)
		Map<Long, Long> catalogIdToInventoryId = mapCatalogIdToInventoryId(userId, ingredients);

		// 응답 생성
		List<IngredientResponse> ingredientResponses = ingredients.stream()
			.map(ing -> new IngredientResponse(
				ing.getIngredientCatalogId(),
				catalogIdToInventoryId.get(ing.getIngredientCatalogId()),
				getIngredientName(ing.getIngredientCatalogId())
			))
			.toList();

		List<StepResponse> stepResponses = steps.stream()
			.map(step -> new StepResponse(step.getStepOrder(), step.getDescription()))
			.toList();

		return new RecipeDetailResponse(
			recipeId,
			recipe.getTitle(),
			recipe.getCategory().name(),
			recipe.getCookTime(),
			recipe.getImageUrl(),
			ingredientResponses,
			stepResponses
		);
	}

	private Map<Long, Long> mapCatalogIdToInventoryId(Long userId,
		List<RecipeIngredient> ingredients) {
		Map<Long, Long> result = new HashMap<>();

		for (RecipeIngredient ingredient : ingredients) {
			Long catalogId = ingredient.getIngredientCatalogId();

			// 사용자의 ACTIVE 재고 중 가장 빠른 소비기한 찾기 (3-4의 3)
			List<InventoryItem> userInventory = inventoryItemRepository.findByUserIdAndIngredientCatalogId(
				userId, catalogId);

			Optional<InventoryItem> active = userInventory.stream()
				.filter(item -> isActiveStatus(item))
				.min((a, b) -> {
					// 소비기한이 없는 것은 뒤로
					if (a.getExpiresOn() == null && b.getExpiresOn() == null) {
						return a.getId().compareTo(b.getId());
					}
					if (a.getExpiresOn() == null) return 1;
					if (b.getExpiresOn() == null) return -1;
					// 소비기한 빠른 순, 같으면 ID 작은 순
					int dateCompare = a.getExpiresOn().compareTo(b.getExpiresOn());
					return dateCompare != 0 ? dateCompare : a.getId().compareTo(b.getId());
				});

			active.ifPresent(item -> result.put(catalogId, item.getId()));
		}

		return result;
	}

	private String getIngredientName(Long catalogId) {
		return ingredientCatalogService.getNameById(catalogId);
	}

	private boolean isActiveStatus(InventoryItem item) {
		return itemStatusRepository.findById(item.getStatusId())
			.map(status -> "active".equals(status.getCode()))
			.orElse(false);
	}
}
