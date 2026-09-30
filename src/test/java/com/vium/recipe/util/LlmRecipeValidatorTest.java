package com.vium.recipe.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vium.recipe.dto.LlmRecipeResponse.RecipeDto;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class LlmRecipeValidatorTest {

	@Autowired
	private LlmRecipeValidator validator;

	@Test
	void testValidRecipe() {
		RecipeDto recipe = new RecipeDto(
			"당근 크림 파스타",
			"WESTERN",
			20,
			Arrays.asList(3, 7, 5, 11),
			Arrays.asList("끓는 물에 면을 넣어", "다른 재료와 볶아"),
			"carrot cream pasta",
			"임박한 재료를 활용할 수 있습니다"
		);

		Map<String, Integer> need = new HashMap<>();
		need.put("WESTERN", 3);
		List<Long> userIds = Arrays.asList(3L, 5L, 7L, 11L);

		List<RecipeDto> result = validator.validate(Arrays.asList(recipe), userIds, need);

		assertEquals(1, result.size());
		assertEquals("당근 크림 파스타", result.get(0).title());
	}

	@Test
	void testInvalidCategoryRejected() {
		RecipeDto recipe = new RecipeDto(
			"딸기 타르트",
			"ITALIAN",
			25,
			Arrays.asList(1, 2),
			Arrays.asList("믹싱", "굽기"),
			"strawberry tart",
			"딸기 활용"
		);

		Map<String, Integer> need = new HashMap<>();
		need.put("DESSERT", 1);
		List<Long> userIds = Arrays.asList(1L, 2L);

		List<RecipeDto> result = validator.validate(Arrays.asList(recipe), userIds, need);

		assertEquals(0, result.size());
	}

	@Test
	void testReasonTrimmedTo200Chars() {
		StringBuilder longReason = new StringBuilder();
		for (int i = 0; i < 300; i++) {
			longReason.append("a");
		}

		RecipeDto recipe = new RecipeDto(
			"테스트 요리",
			"KOREAN",
			15,
			Arrays.asList(1),
			Arrays.asList("단계1"),
			"test",
			longReason.toString()
		);

		Map<String, Integer> need = new HashMap<>();
		need.put("KOREAN", 1);
		List<Long> userIds = Arrays.asList(1L);

		List<RecipeDto> result = validator.validate(Arrays.asList(recipe), userIds, need);

		assertEquals(1, result.size());
		assertTrue(result.get(0).reason().length() <= 200);
	}

	@Test
	void testMissingIngredientRejected() {
		RecipeDto recipe = new RecipeDto(
			"파스타",
			"WESTERN",
			20,
			Arrays.asList(3, 7, 5, 999),
			Arrays.asList("끓이기", "섞기"),
			"pasta",
			"테스트"
		);

		Map<String, Integer> need = new HashMap<>();
		need.put("WESTERN", 1);
		List<Long> userIds = Arrays.asList(3L, 5L, 7L);

		List<RecipeDto> result = validator.validate(Arrays.asList(recipe), userIds, need);

		assertEquals(0, result.size());
	}

	@Test
	void testCategoryLimitEnforced() {
		RecipeDto recipe1 = new RecipeDto("요리1", "WESTERN", 15, Arrays.asList(1), Arrays.asList("단계"), "a", null);
		RecipeDto recipe2 = new RecipeDto("요리2", "WESTERN", 16, Arrays.asList(2), Arrays.asList("단계"), "b", null);
		RecipeDto recipe3 = new RecipeDto("요리3", "WESTERN", 17, Arrays.asList(3), Arrays.asList("단계"), "c", null);
		RecipeDto recipe4 = new RecipeDto("요리4", "WESTERN", 18, Arrays.asList(4), Arrays.asList("단계"), "d", null);

		Map<String, Integer> need = new HashMap<>();
		need.put("WESTERN", 2);
		List<Long> userIds = Arrays.asList(1L, 2L, 3L, 4L);

		List<RecipeDto> result = validator.validate(
			Arrays.asList(recipe1, recipe2, recipe3, recipe4), userIds, need
		);

		assertEquals(2, result.size());
	}
}
