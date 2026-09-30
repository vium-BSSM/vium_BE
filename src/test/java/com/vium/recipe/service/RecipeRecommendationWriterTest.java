package com.vium.recipe.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vium.recipe.dto.GeneratedRecipe;
import com.vium.recipe.dto.ImageSearchResult;
import com.vium.recipe.dto.LlmRecipeResponse.RecipeDto;
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

		recipeWriter.saveRecommendations(userId, List.of(), List.of(new GeneratedRecipe(
			new RecipeDto("당근 크림 파스타", "WESTERN", 20, List.of(1), List.of("Cook"), "pasta", "reason"),
			new ImageSearchResult("https://images.unsplash.com/test", "author", "https://example.com", null))),
			batchId, inventoryHash, suggestedAt);

		List<RecipeSuggestion> suggestions = recipeSuggestionRepository.findByUserIdAndBatchIdOrderByRecipeIdAsc(
			userId, batchId);
		assertEquals(1, suggestions.size());
		assertEquals(userId, suggestions.get(0).getUserId());
		assertEquals(batchId, suggestions.get(0).getBatchId());
		var saved = recipeRepository.findById(suggestions.get(0).getRecipeId()).orElseThrow();
		assertEquals("https://images.unsplash.com/test", saved.getImageUrl());
		assertEquals("author", saved.getImageAuthorName());
	}

	@Test
	@Transactional
	void testSaveRecommendations_WithoutImage() {
		// 이미지 없는 레시피 저장
		Long userId = 2L;
		UUID batchId = UUID.randomUUID();
		String inventoryHash = "test_hash_2";
		LocalDateTime suggestedAt = LocalDateTime.now();

		recipeWriter.saveRecommendations(userId, List.of(), List.of(new GeneratedRecipe(
			new RecipeDto("감자 수프", "KOREAN", 30, List.of(1), List.of("Cook"), "soup", null), null)),
			batchId, inventoryHash, suggestedAt);

		List<RecipeSuggestion> suggestions = recipeSuggestionRepository.findByUserIdAndBatchIdOrderByRecipeIdAsc(
			userId, batchId);
		assertEquals(1, suggestions.size());
		assertEquals(inventoryHash, suggestions.get(0).getInventoryHash());
	}
}
