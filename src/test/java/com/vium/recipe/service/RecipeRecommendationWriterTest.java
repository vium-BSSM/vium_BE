package com.vium.recipe.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vium.recipe.entity.Recipe;
import com.vium.recipe.dto.GeneratedRecipe;
import com.vium.recipe.dto.ImageSearchResult;
import com.vium.recipe.dto.LlmRecipeResponse.RecipeDto;
import com.vium.recipe.entity.RecipeCategory;
import com.vium.recipe.entity.RecipeSuggestion;
import com.vium.recipe.repository.RecipeRepository;
import com.vium.recipe.repository.RecipeSuggestionRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
class RecipeRecommendationWriterTest {

	@Autowired
	private RecipeRecommendationWriter recipeWriter;

	@Autowired
	private RecipeRepository recipeRepository;

	@Autowired
	private RecipeSuggestionRepository recipeSuggestionRepository;

	@Test
	@Transactional
	void testSaveRecommendations_WithImage() {
		// 이미지 있는 레시피 저장
		Long userId = 1L;
		UUID batchId = UUID.randomUUID();
		String inventoryHash = "test_hash";
		LocalDateTime suggestedAt = LocalDateTime.now();

		Recipe recipe = Recipe.builder()
			.title("당근 크림 파스타")
			.category(RecipeCategory.WESTERN)
			.cookTime(20)
			.source("AI")
			.imageUrl("https://images.unsplash.com/...")
			.build();

		recipeWriter.saveRecommendations(userId, List.of(), List.of(new GeneratedRecipe(
			new RecipeDto(recipe.getTitle(), "WESTERN", 20, List.of(1), List.of("Cook"), "pasta", "reason"),
			new ImageSearchResult(recipe.getImageUrl(), "author", "https://example.com", null))),
			batchId, inventoryHash, suggestedAt);

		List<RecipeSuggestion> suggestions = recipeSuggestionRepository.findByUserIdAndBatchIdOrderByRecipeIdAsc(
			userId, batchId);
		assertEquals(1, suggestions.size());
		assertEquals(userId, suggestions.get(0).getUserId());
		assertEquals(batchId, suggestions.get(0).getBatchId());
	}

	@Test
	@Transactional
	void testSaveRecommendations_WithoutImage() {
		// 이미지 없는 레시피 저장
		Long userId = 2L;
		UUID batchId = UUID.randomUUID();
		String inventoryHash = "test_hash_2";
		LocalDateTime suggestedAt = LocalDateTime.now();

		Recipe recipe = Recipe.builder()
			.title("감자 수프")
			.category(RecipeCategory.KOREAN)
			.cookTime(30)
			.source("AI")
			.build();

		recipeWriter.saveRecommendations(userId, List.of(), List.of(new GeneratedRecipe(
			new RecipeDto(recipe.getTitle(), "KOREAN", 30, List.of(1), List.of("Cook"), "soup", null), null)),
			batchId, inventoryHash, suggestedAt);

		List<RecipeSuggestion> suggestions = recipeSuggestionRepository.findByUserIdAndBatchIdOrderByRecipeIdAsc(
			userId, batchId);
		assertEquals(1, suggestions.size());
		assertEquals(inventoryHash, suggestions.get(0).getInventoryHash());
	}
}
