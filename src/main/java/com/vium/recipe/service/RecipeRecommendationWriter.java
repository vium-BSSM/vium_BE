package com.vium.recipe.service;

import com.vium.recipe.entity.Recipe;
import com.vium.recipe.dto.GeneratedRecipe;
import com.vium.recipe.entity.RecipeSuggestion;
import com.vium.recipe.repository.RecipeIngredientRepository;
import com.vium.recipe.repository.RecipeStepRepository;
import com.vium.recipe.util.RecipeMapper;
import com.vium.recipe.repository.RecipeRepository;
import com.vium.recipe.repository.RecipeSuggestionRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RecipeRecommendationWriter {

	private final RecipeRepository recipeRepository;
	private final RecipeSuggestionRepository recipeSuggestionRepository;
	private final RecipeIngredientRepository recipeIngredientRepository;
	private final RecipeStepRepository recipeStepRepository;
	private final RecipeMapper recipeMapper;

	@Transactional
	public void saveRecommendations(Long userId, List<Recipe> recipes, List<GeneratedRecipe> generatedRecipes,
		UUID batchId, String inventoryHash,
		LocalDateTime suggestedAt) {

		for (Recipe recipe : recipes) {
			saveSuggestion(userId, recipe.getId(), batchId, inventoryHash, suggestedAt, null);
		}
		for (GeneratedRecipe generated : generatedRecipes) {
			Recipe saved = recipeRepository.save(recipeMapper.toRecipeEntity(generated.recipe(), generated.image()));
			recipeIngredientRepository.saveAll(recipeMapper.toRecipeIngredients(saved, generated.recipe()));
			recipeStepRepository.saveAll(recipeMapper.toRecipeSteps(saved, generated.recipe()));
			saveSuggestion(userId, saved.getId(), batchId, inventoryHash, suggestedAt, generated.recipe().reason());
		}
	}

	private void saveSuggestion(Long userId, Long recipeId, UUID batchId, String inventoryHash,
		LocalDateTime suggestedAt, String reason) {
		recipeSuggestionRepository.save(RecipeSuggestion.builder()
			.userId(userId)
			.recipeId(recipeId)
			.batchId(batchId)
			.inventoryHash(inventoryHash)
			.suggestedAt(suggestedAt)
			.reason(reason)
			.build());
	}
}
