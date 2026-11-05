package com.vium.recipe.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vium.recipe.dto.LlmRecipeDetailResponse;
import com.vium.recipe.dto.LlmRecipeDetailResponse.IngredientAmount;
import com.vium.recipe.dto.LlmRecipeDetailResponse.SeasoningItem;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RecipeDetailValidatorTest {

	private final RecipeDetailValidator validator = new RecipeDetailValidator();

	private static final List<String> SIX_STEPS = List.of("a", "b", "c", "d", "e", "f");

	@Test
	void dropsSeasoningThatDuplicatesAnExistingIngredientName() {
		var response = new LlmRecipeDetailResponse(
			List.of(new IngredientAmount(1L, "1개")),
			List.of(new SeasoningItem("계란", "1개"), new SeasoningItem("소금", "한 꼬집")),
			SIX_STEPS);

		var result = validator.validate(response, Set.of(1L), Set.of("계란"));

		assertThat(result.seasonings()).extracting(SeasoningItem::name).containsExactly("소금");
	}

	@Test
	void keepsSeasoningsWhenNoNameOverlaps() {
		var response = new LlmRecipeDetailResponse(
			List.of(new IngredientAmount(1L, "1개")),
			List.of(new SeasoningItem("소금", "한 꼬집"), new SeasoningItem("후추", "약간")),
			SIX_STEPS);

		var result = validator.validate(response, Set.of(1L), Set.of("계란"));

		assertThat(result.seasonings()).extracting(SeasoningItem::name).containsExactly("소금", "후추");
	}

	@Test
	void truncatesExcessSeasoningsToFirstTen() {
		List<SeasoningItem> twelve = java.util.stream.IntStream.range(0, 12)
			.mapToObj(i -> new SeasoningItem("양념" + i, "약간"))
			.toList();
		var response = new LlmRecipeDetailResponse(List.of(new IngredientAmount(1L, "1개")), twelve, SIX_STEPS);

		var result = validator.validate(response, Set.of(1L), Set.of());

		assertThat(result.seasonings()).hasSize(10);
		assertThat(result.seasonings()).extracting(SeasoningItem::name)
			.containsExactly("양념0", "양념1", "양념2", "양념3", "양념4", "양념5", "양념6", "양념7", "양념8", "양념9");
	}

	@Test
	void rejectsBlankSeasoningName() {
		var response = new LlmRecipeDetailResponse(
			List.of(new IngredientAmount(1L, "1개")),
			List.of(new SeasoningItem(" ", "약간")),
			SIX_STEPS);

		assertThatThrownBy(() -> validator.validate(response, Set.of(1L), Set.of()))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsBlankSeasoningAmount() {
		var response = new LlmRecipeDetailResponse(
			List.of(new IngredientAmount(1L, "1개")),
			List.of(new SeasoningItem("소금", " ")),
			SIX_STEPS);

		assertThatThrownBy(() -> validator.validate(response, Set.of(1L), Set.of()))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsNullSeasoningsList() {
		var response = new LlmRecipeDetailResponse(List.of(new IngredientAmount(1L, "1개")), null, SIX_STEPS);

		assertThatThrownBy(() -> validator.validate(response, Set.of(1L), Set.of()))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsStepsOutsideSixToTwelveRange() {
		var response = new LlmRecipeDetailResponse(
			List.of(new IngredientAmount(1L, "1개")), List.of(), List.of("a", "b", "c"));

		assertThatThrownBy(() -> validator.validate(response, Set.of(1L), Set.of()))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsIngredientCatalogIdMismatch() {
		var response = new LlmRecipeDetailResponse(
			List.of(new IngredientAmount(999L, "1개")), List.of(), SIX_STEPS);

		assertThatThrownBy(() -> validator.validate(response, Set.of(1L), Set.of()))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
