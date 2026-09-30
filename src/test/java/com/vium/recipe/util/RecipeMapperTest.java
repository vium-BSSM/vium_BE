package com.vium.recipe.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.vium.recipe.dto.ImageSearchResult;
import com.vium.recipe.dto.LlmRecipeResponse.RecipeDto;
import com.vium.recipe.entity.Recipe;
import com.vium.recipe.entity.RecipeIngredient;
import com.vium.recipe.entity.RecipeStep;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecipeMapperTest {

	private final RecipeMapper mapper = new RecipeMapper();

	@Test
	void testToRecipeEntity_WithImage() {
		// 이미지 있음
		RecipeDto dto = new RecipeDto(
			"당근 크림 파스타",
			"WESTERN",
			20,
			Arrays.asList(3, 5, 7),
			Arrays.asList("삶아주세요", "볶아주세요"),
			"carrot cream pasta",
			"당근을 활용한 레시피"
		);
		ImageSearchResult imageResult = new ImageSearchResult(
			"https://images.unsplash.com/...",
			"Hong Gildong",
			"https://unsplash.com/@hong",
			null
		);

		Recipe recipe = mapper.toRecipeEntity(dto, imageResult);

		assertEquals("당근 크림 파스타", recipe.getTitle());
		assertEquals("WESTERN", recipe.getCategory().toString());
		assertEquals(20, recipe.getCookTime());
		assertEquals("https://images.unsplash.com/...", recipe.getImageUrl());
		assertEquals("Hong Gildong", recipe.getImageAuthorName());
		assertEquals("https://unsplash.com/@hong", recipe.getImageAuthorUrl());
		assertEquals("AI", recipe.getSource());
		assertNull(recipe.getDescription());
	}

	@Test
	void testToRecipeEntity_WithoutImage() {
		// 이미지 없음
		RecipeDto dto = new RecipeDto(
			"감자 수프",
			"KOREAN",
			30,
			Arrays.asList(7, 5),
			Arrays.asList("끓여주세요"),
			"potato soup",
			"감자로 만드는 수프"
		);

		Recipe recipe = mapper.toRecipeEntity(dto, null);

		assertEquals("감자 수프", recipe.getTitle());
		assertNull(recipe.getImageUrl());
		assertNull(recipe.getImageAuthorName());
		assertNull(recipe.getImageAuthorUrl());
		assertEquals("AI", recipe.getSource());
	}

	@Test
	void testToRecipeIngredients() {
		Recipe recipe = Recipe.builder()
			.title("Test Recipe")
			.category(null)
			.cookTime(20)
			.source("AI")
			.build();

		RecipeDto dto = new RecipeDto(
			"Test Recipe",
			"WESTERN",
			20,
			Arrays.asList(3, 5, 7, 11),
			Arrays.asList("step1", "step2"),
			"test",
			"test reason"
		);

		List<RecipeIngredient> ingredients = mapper.toRecipeIngredients(recipe, dto);

		assertEquals(4, ingredients.size());
		assertEquals(3L, ingredients.get(0).getIngredientCatalogId());
		assertEquals(5L, ingredients.get(1).getIngredientCatalogId());
		assertEquals(7L, ingredients.get(2).getIngredientCatalogId());
		assertEquals(11L, ingredients.get(3).getIngredientCatalogId());
	}

	@Test
	void testToRecipeSteps() {
		Recipe recipe = Recipe.builder()
			.title("Test Recipe")
			.category(null)
			.cookTime(20)
			.source("AI")
			.build();

		RecipeDto dto = new RecipeDto(
			"Test Recipe",
			"WESTERN",
			20,
			Arrays.asList(3, 5),
			Arrays.asList("끓이세요", "볶으세요", "담으세요"),
			"test",
			"test reason"
		);

		List<RecipeStep> steps = mapper.toRecipeSteps(recipe, dto);

		assertEquals(3, steps.size());
		assertEquals(1, steps.get(0).getStepOrder());
		assertEquals("끓이세요", steps.get(0).getDescription());
		assertEquals(2, steps.get(1).getStepOrder());
		assertEquals("볶으세요", steps.get(1).getDescription());
		assertEquals(3, steps.get(2).getStepOrder());
		assertEquals("담으세요", steps.get(2).getDescription());
	}
}
