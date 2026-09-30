package com.vium.recipe.service;

import com.vium.recipe.entity.Recipe;
import com.vium.recipe.entity.RecipeSuggestion;
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

	@Transactional
	public void saveRecommendations(Long userId, List<Recipe> recipes, UUID batchId, String inventoryHash,
		LocalDateTime suggestedAt) {

		for (Recipe recipe : recipes) {
			if (recipe.getId() == null) {
				Recipe saved = recipeRepository.save(recipe);

				RecipeSuggestion suggestion = RecipeSuggestion.builder()
					.userId(userId)
					.recipeId(saved.getId())
					.batchId(batchId)
					.inventoryHash(inventoryHash)
					.suggestedAt(suggestedAt)
					.reason(null)
					.build();
				recipeSuggestionRepository.save(suggestion);
			} else {
				RecipeSuggestion suggestion = RecipeSuggestion.builder()
					.userId(userId)
					.recipeId(recipe.getId())
					.batchId(batchId)
					.inventoryHash(inventoryHash)
					.suggestedAt(suggestedAt)
					.reason(null)
					.build();
				recipeSuggestionRepository.save(suggestion);
			}
		}
	}
}
