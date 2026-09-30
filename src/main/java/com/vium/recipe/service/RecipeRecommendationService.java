package com.vium.recipe.service;

import com.vium.global.code.ItemStatusRepository;
import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.ingredient.service.IngredientCatalogService;
import com.vium.inventory.entity.InventoryItem;
import com.vium.inventory.repository.InventoryItemRepository;
import com.vium.recipe.client.ImageSearchClient;
import com.vium.recipe.client.LlmRecipeClient;
import com.vium.recipe.dto.ImageSearchResult;
import com.vium.recipe.dto.LlmRecipeRequest;
import com.vium.recipe.dto.LlmRecipeRequest.IngredientInfo;
import com.vium.recipe.dto.LlmRecipeResponse;
import com.vium.recipe.dto.LlmRecipeResponse.RecipeDto;
import com.vium.recipe.dto.RecommendedRecipesResponse;
import com.vium.recipe.dto.RecommendedRecipesResponse.RecipeCard;
import com.vium.recipe.entity.Recipe;
import com.vium.recipe.entity.RecipeCategory;
import com.vium.recipe.entity.RecipeIngredient;
import com.vium.recipe.entity.RecipeStep;
import com.vium.recipe.entity.RecipeSuggestion;
import com.vium.recipe.repository.RecipeIngredientRepository;
import com.vium.recipe.repository.RecipeQueryRepository;
import com.vium.recipe.repository.RecipeRepository;
import com.vium.recipe.repository.RecipeStepRepository;
import com.vium.recipe.repository.RecipeSuggestionRepository;
import com.vium.recipe.util.InventoryHashCalculator;
import com.vium.recipe.util.LlmRecipeValidator;
import com.vium.recipe.util.RecipeMapper;
import com.vium.recipe.util.RecipeUrgencyScorer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class RecipeRecommendationService {

	private final InventoryItemRepository inventoryItemRepository;
	private final RecipeQueryRepository recipeQueryRepository;
	private final RecipeRepository recipeRepository;
	private final RecipeIngredientRepository recipeIngredientRepository;
	private final RecipeStepRepository recipeStepRepository;
	private final RecipeSuggestionRepository recipeSuggestionRepository;
	private final LlmRecipeClient llmRecipeClient;
	private final ImageSearchClient imageSearchClient;
	private final RecipeUrgencyScorer urgencyScorer;
	private final LlmRecipeValidator llmRecipeValidator;
	private final RecipeMapper recipeMapper;
	private final ItemStatusRepository itemStatusRepository;
	private final IngredientCatalogService ingredientCatalogService;
	private final RecipeRecommendationWriter recipeWriter;

	private static final int RECIPES_PER_CATEGORY = 3;
	private static final int MAX_INGREDIENTS_FOR_LLM = 20;

	public RecommendedRecipesResponse getRecommendedRecipes(Long userId, String categoryParam) {
		List<InventoryItem> activeInventory = getActiveInventory(userId);
		if (activeInventory.isEmpty()) {
			return new RecommendedRecipesResponse(Collections.emptyList());
		}

		List<Long> userCatalogIds = activeInventory.stream()
			.map(InventoryItem::getIngredientCatalogId)
			.filter(java.util.Objects::nonNull)
			.distinct()
			.toList();

		String inventoryHash = InventoryHashCalculator.calculateHash(userCatalogIds);

		Optional<RecipeSuggestion> latestSuggestion = recipeSuggestionRepository
			.findFirstByUserIdOrderBySuggestedAtDesc(userId);

		if (latestSuggestion.isPresent() && latestSuggestion.get().getInventoryHash().equals(inventoryHash)) {
			UUID batchId = latestSuggestion.get().getBatchId();
			return buildResponse(userId, batchId, categoryParam);
		}

		Map<Long, LocalDate> inventoryMap = buildInventoryMap(activeInventory);
		UUID newBatchId = UUID.randomUUID();
		LocalDateTime suggestedAt = LocalDateTime.now();

		Map<RecipeCategory, List<Recipe>> reusableRecipes = searchReusableRecipes(userCatalogIds);
		List<Recipe> allRecipes = new ArrayList<>(
			reusableRecipes.values().stream().flatMap(List::stream).collect(Collectors.toList())
		);

		List<Recipe> llmRecipes = callLlmIfNeeded(userId, reusableRecipes, userCatalogIds, inventoryMap);
		allRecipes.addAll(llmRecipes);

		if (allRecipes.isEmpty()) {
			throw new BusinessException(ErrorCode.RECIPE_GENERATION_FAILED);
		}

		recipeWriter.saveRecommendations(userId, allRecipes, newBatchId, inventoryHash, suggestedAt);

		return buildResponse(userId, newBatchId, categoryParam);
	}

	private List<InventoryItem> getActiveInventory(Long userId) {
		Short activeStatusId = itemStatusRepository.findByCode("active")
			.map(status -> status.getId())
			.orElse((short) 1);

		return inventoryItemRepository.findAll().stream()
			.filter(item -> item.getUserId().equals(userId))
			.filter(item -> item.getStatusId().equals(activeStatusId))
			.toList();
	}

	private Map<Long, LocalDate> buildInventoryMap(List<InventoryItem> inventory) {
		Map<Long, LocalDate> map = new HashMap<>();
		inventory.stream()
			.filter(item -> item.getIngredientCatalogId() != null)
			.forEach(item -> {
				Long catalogId = item.getIngredientCatalogId();
				LocalDate expiresOn = item.getExpiresOn();
				if (!map.containsKey(catalogId) || (expiresOn != null &&
					(map.get(catalogId) == null || expiresOn.isBefore(map.get(catalogId))))) {
					map.put(catalogId, expiresOn);
				}
			});
		return map;
	}

	private Map<RecipeCategory, List<Recipe>> searchReusableRecipes(List<Long> userCatalogIds) {
		Map<RecipeCategory, List<Recipe>> result = new HashMap<>();

		for (RecipeCategory category : RecipeCategory.values()) {
			if (category.isFilterOnly()) continue;

			List<Recipe> candidates = recipeQueryRepository.findReusableRecipes(category, userCatalogIds);
			result.put(category, candidates.stream()
				.limit(RECIPES_PER_CATEGORY)
				.toList());
		}

		return result;
	}

	private List<Recipe> callLlmIfNeeded(Long userId, Map<RecipeCategory, List<Recipe>> reusableRecipes,
		List<Long> userCatalogIds, Map<Long, LocalDate> inventoryMap) {

		Map<String, Integer> need = new HashMap<>();
		Set<String> excludeTitles = new HashSet<>();

		for (RecipeCategory category : RecipeCategory.values()) {
			if (category.isFilterOnly()) continue;

			int reusableCount = reusableRecipes.getOrDefault(category, Collections.emptyList()).size();
			int needed = RECIPES_PER_CATEGORY - reusableCount;

			if (needed > 0) {
				need.put(category.toString(), needed);
			}

			reusableRecipes.getOrDefault(category, Collections.emptyList())
				.forEach(r -> excludeTitles.add(r.getTitle()));
		}

		if (need.isEmpty()) {
			return Collections.emptyList();
		}

		try {
			List<InventoryItem> activeInventory = getActiveInventory(userId);
			List<IngredientInfo> ingredientInfos = buildIngredientList(activeInventory, inventoryMap);
			LlmRecipeRequest request = new LlmRecipeRequest(ingredientInfos, need, new ArrayList<>(excludeTitles));

			LlmRecipeResponse response = callLlmWithRetry(request);
			List<RecipeDto> validatedDtos = llmRecipeValidator.validate(response.recipes(), userCatalogIds, need);

			List<Recipe> recipes = new ArrayList<>();
			for (RecipeDto dto : validatedDtos) {
				ImageSearchResult imageResult = searchImage(dto.imageKeyword(), dto.category());
				Recipe recipe = recipeMapper.toRecipeEntity(dto, imageResult);
				List<RecipeIngredient> ingredients = recipeMapper.toRecipeIngredients(recipe, dto);
				List<RecipeStep> steps = recipeMapper.toRecipeSteps(recipe, dto);

				recipes.add(recipe);
			}

			return recipes;
		} catch (Exception e) {
			log.warn("LLM 호출 실패 (재사용 레시피 있으면 계속): {}", e.getMessage());
			return Collections.emptyList();
		}
	}

	private LlmRecipeResponse callLlmWithRetry(LlmRecipeRequest request) {
		try {
			return llmRecipeClient.generateRecipes(request);
		} catch (Exception e) {
			log.warn("LLM 첫 시도 실패, 재시도: {}", e.getMessage());
			try {
				Thread.sleep(2000);
				return llmRecipeClient.generateRecipes(request);
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				throw new RuntimeException("LLM 호출 재시도 중 인터럽트됨", ie);
			} catch (Exception retryException) {
				throw retryException;
			}
		}
	}

	private List<IngredientInfo> buildIngredientList(List<InventoryItem> inventory,
		Map<Long, LocalDate> inventoryMap) {

		return inventory.stream()
			.filter(item -> item.getIngredientCatalogId() != null)
			.map(item -> {
				Long catalogId = item.getIngredientCatalogId();
				String name = item.getCustomName() != null ? item.getCustomName() :
					ingredientCatalogService.getNameById(catalogId);
				if (name == null) name = "재료";
				LocalDate expiresOn = inventoryMap.get(catalogId);
				Integer expireInDays = expiresOn != null ?
					(int) java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), expiresOn) : null;
				return new IngredientInfo(catalogId, name, expireInDays);
			})
			.distinct()
			.limit(MAX_INGREDIENTS_FOR_LLM)
			.collect(Collectors.toList());
	}

	private ImageSearchResult searchImage(String imageKeyword, String category) {
		try {
			ImageSearchResult result = imageSearchClient.searchImage(imageKeyword);
			if (result == null) {
				String fallbackKeyword = getFallbackKeyword(category);
				result = imageSearchClient.searchImage(fallbackKeyword);
			}
			if (result != null) {
				try {
					imageSearchClient.recordDownload(result.downloadUrl());
				} catch (Exception e) {
					log.debug("다운로드 기록 호출 실패 (무시): {}", e.getMessage());
				}
			}
			return result;
		} catch (Exception e) {
			log.debug("이미지 검색 실패 (무시): {}", e.getMessage());
			return null;
		}
	}

	private String getFallbackKeyword(String category) {
		return switch (category) {
			case "KOREAN" -> "korean food";
			case "CHINESE" -> "chinese food";
			case "WESTERN" -> "western food";
			case "JAPANESE" -> "japanese food";
			case "DESSERT" -> "dessert";
			default -> "food";
		};
	}


	private RecommendedRecipesResponse buildResponse(Long userId, UUID batchId, String categoryParam) {
		List<RecipeSuggestion> suggestions = recipeSuggestionRepository.findByUserIdAndBatchIdOrderByRecipeIdAsc(
			userId, batchId);

		Map<Long, Recipe> recipesById = new HashMap<>();
		Map<Long, List<RecipeIngredient>> ingredientsByRecipe = new HashMap<>();

		for (RecipeSuggestion suggestion : suggestions) {
			Optional<Recipe> recipe = recipeRepository.findById(suggestion.getRecipeId());
			recipe.ifPresent(r -> {
				recipesById.put(r.getId(), r);
				List<RecipeIngredient> ingredients = recipeIngredientRepository.findByRecipeIdOrderByIdAsc(r.getId());
				ingredientsByRecipe.put(r.getId(), ingredients);
			});
		}

		Map<Long, LocalDate> inventoryMap = buildInventoryMap(getActiveInventory(userId));

		Map<Long, Integer> scoreMap = new HashMap<>();
		for (Recipe recipe : recipesById.values()) {
			List<Long> catalogIds = ingredientsByRecipe.getOrDefault(recipe.getId(), Collections.emptyList())
				.stream()
				.map(RecipeIngredient::getIngredientCatalogId)
				.toList();
			int score = urgencyScorer.calculateScore(catalogIds, inventoryMap);
			scoreMap.put(recipe.getId(), score);
		}

		RecipeCategory filterCategory = parseCategory(categoryParam);
		List<RecipeCard> cards = recipesById.values().stream()
			.filter(r -> filterCategory == RecipeCategory.ALL || r.getCategory() == filterCategory)
			.sorted((a, b) -> {
				int scoreCompare = Integer.compare(scoreMap.get(b.getId()), scoreMap.get(a.getId()));
				if (scoreCompare != 0) return scoreCompare;
				int timeCompare = Integer.compare(a.getCookTime(), b.getCookTime());
				if (timeCompare != 0) return timeCompare;
				return Long.compare(a.getId(), b.getId());
			})
			.map(recipe -> buildRecipeCard(recipe, ingredientsByRecipe.getOrDefault(recipe.getId(), Collections.emptyList())))
			.toList();

		return new RecommendedRecipesResponse(cards);
	}

	private RecipeCard buildRecipeCard(Recipe recipe, List<RecipeIngredient> ingredients) {
		List<RecipeCard.IngredientInfo> ingredientCards = ingredients.stream()
			.map(ri -> {
				String name = ingredientCatalogService.getNameById(ri.getIngredientCatalogId());
				if (name == null) name = "Unknown";
				return new RecipeCard.IngredientInfo(ri.getIngredientCatalogId(), name);
			})
			.toList();
		return new RecipeCard(
			recipe.getId(),
			recipe.getTitle(),
			recipe.getCategory().toString(),
			recipe.getCookTime(),
			ingredientCards
		);
	}

	private RecipeCategory parseCategory(String categoryParam) {
		try {
			return RecipeCategory.valueOf(categoryParam);
		} catch (IllegalArgumentException e) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, "Invalid category: " + categoryParam);
		}
	}
}
