package com.vium.recipe.util;

import com.vium.recipe.dto.ImageSearchResult;
import com.vium.recipe.dto.LlmRecipeResponse.RecipeDto;
import com.vium.recipe.entity.Recipe;
import com.vium.recipe.entity.RecipeCategory;
import com.vium.recipe.entity.RecipeIngredient;
import com.vium.recipe.entity.RecipeStep;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class RecipeMapper {

	public Recipe toRecipeEntity(RecipeDto dto, ImageSearchResult imageResult) {
		Recipe recipe = Recipe.builder()
			.title(dto.title())
			.description(null)
			.category(RecipeCategory.valueOf(dto.category()))
			.cookTime(dto.cookTime())
			.imageUrl(imageResult != null ? imageResult.imageUrl() : null)
			.source("AI")
			.build();

		if (imageResult != null) {
			recipe.setImageAuthorName(imageResult.authorName());
			recipe.setImageAuthorUrl(imageResult.authorUrl());
		}

		return recipe;
	}

	public List<RecipeIngredient> toRecipeIngredients(Recipe recipe, RecipeDto dto) {
		List<RecipeIngredient> ingredients = new ArrayList<>();

		if (dto.ingredientCatalogIds() != null) {
			for (int i = 0; i < dto.ingredientCatalogIds().size(); i++) {
				Integer catalogId = dto.ingredientCatalogIds().get(i);
				RecipeIngredient ingredient = RecipeIngredient.builder()
					.recipeId(recipe.getId())
					.ingredientCatalogId(catalogId.longValue())
					.build();
				ingredients.add(ingredient);
			}
		}

		return ingredients;
	}

	public List<RecipeStep> toRecipeSteps(Recipe recipe, RecipeDto dto) {
		List<RecipeStep> steps = new ArrayList<>();

		if (dto.steps() != null) {
			for (int i = 0; i < dto.steps().size(); i++) {
				RecipeStep step = RecipeStep.builder()
					.recipeId(recipe.getId())
					.stepOrder(i + 1)
					.description(dto.steps().get(i))
					.build();
				steps.add(step);
			}
		}

		return steps;
	}
}
