package com.vium.recipe.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class RecipeUrgencyScorerTest {

	@Autowired
	private RecipeUrgencyScorer scorer;

	@Test
	void testRecipeScoreWithUrgentIngredient() {
		LocalDate today = LocalDate.now();
		List<Long> ingredientIds = Arrays.asList(1L, 2L, 3L);
		Map<Long, LocalDate> inventory = new HashMap<>();
		inventory.put(1L, today.plusDays(1));
		inventory.put(2L, today.plusDays(5));
		inventory.put(3L, today.plusDays(10));

		int score = scorer.calculateScore(ingredientIds, inventory);
		assertEquals(4, score);
	}

	@Test
	void testAllIngredientsUrgent() {
		LocalDate today = LocalDate.now();
		List<Long> ingredientIds = Arrays.asList(1L, 2L, 3L);
		Map<Long, LocalDate> inventory = new HashMap<>();
		inventory.put(1L, today);
		inventory.put(2L, today.plusDays(1));
		inventory.put(3L, today.minusDays(1));

		int score = scorer.calculateScore(ingredientIds, inventory);
		assertEquals(9, score);
	}

	@Test
	void testNoUrgentIngredients() {
		LocalDate today = LocalDate.now();
		List<Long> ingredientIds = Arrays.asList(1L, 2L);
		Map<Long, LocalDate> inventory = new HashMap<>();
		inventory.put(1L, today.plusDays(8));
		inventory.put(2L, null);

		int score = scorer.calculateScore(ingredientIds, inventory);
		assertEquals(0, score);
	}

	@Test
	void testMissingInventoryReturnsZero() {
		LocalDate today = LocalDate.now();
		List<Long> ingredientIds = Arrays.asList(1L, 2L);
		Map<Long, LocalDate> inventory = new HashMap<>();

		int score = scorer.calculateScore(ingredientIds, inventory);
		assertEquals(0, score);
	}
}
