package com.vium.recipe.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.auth.service.TokenService;
import com.vium.recipe.client.ImageSearchClient;
import com.vium.recipe.client.LlmRecipeClient;
import com.vium.recipe.dto.GeneratedRecipe;
import com.vium.recipe.dto.LlmRecipeDetailResponse;
import com.vium.recipe.dto.LlmRecipeDetailResponse.IngredientAmount;
import com.vium.recipe.dto.LlmRecipeDetailResponse.SeasoningItem;
import com.vium.recipe.dto.LlmRecipeResponse.RecipeDto;
import com.vium.recipe.repository.RecipeRepository;
import com.vium.recipe.service.RecipeRecommendationWriter;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:recipe-detail-enrichment;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class RecipeDetailEnrichmentIntegrationTest {
	@Autowired private MockMvc mockMvc;
	@Autowired private TokenService tokenService;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private RecipeRecommendationWriter writer;
	@Autowired private RecipeRepository recipeRepository;
	@MockitoBean private LlmRecipeClient llm;
	@MockitoBean private ImageSearchClient images;

	private final RecipeDto baseRecipe = new RecipeDto("Soup", "KOREAN", 10,
		List.of(1), List.of("Prepare", "Cook"), "soup", "Use soon");

	private Long recipeId;

	@BeforeEach
	void setUp() {
		for (String table : List.of("recipe_suggestions", "recipe_steps", "recipe_seasonings", "recipe_ingredients",
			"recipes", "inventory_items", "ingredient_catalog", "units", "item_statuses")) {
			jdbc.update("delete from " + table);
		}
		jdbc.update("insert into units (id,code,name) values (1,'ea','ea')");
		jdbc.update("insert into item_statuses (id,code,name) values (1,'active','active')");
		jdbc.update("insert into ingredient_catalog (id,name,default_unit_id,created_at) values (1,'Egg',1,CURRENT_TIMESTAMP)");

		writer.saveRecommendations(42L, List.of(), List.of(new GeneratedRecipe(baseRecipe, null)),
			UUID.randomUUID(), "hash", LocalDateTime.now());
		recipeId = recipeRepository.findAll().getFirst().getId();
	}

	@Test
	void enrichesDetailOnFirstViewOnlyAndDropsSeasoningDuplicateOfIngredient() throws Exception {
		var detail = new LlmRecipeDetailResponse(
			List.of(new IngredientAmount(1L, "2개")),
			List.of(new SeasoningItem("소금", "한 꼬집"), new SeasoningItem("Egg", "1개")),
			List.of("물을 끓인다", "계란을 넣는다", "소금을 넣는다", "4분간 끓인다", "불을 끈다", "그릇에 담는다"));
		when(llm.enrichRecipeDetail(any())).thenReturn(detail);

		mockMvc.perform(get("/api/me/recipes/{id}", recipeId).header("Authorization", bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.detailReady").value(true))
			.andExpect(jsonPath("$.data.steps.length()").value(6))
			.andExpect(jsonPath("$.data.ingredients[0].amount").value("2개"))
			.andExpect(jsonPath("$.data.seasonings.length()").value(1))
			.andExpect(jsonPath("$.data.seasonings[0].name").value("소금"));

		mockMvc.perform(get("/api/me/recipes/{id}", recipeId).header("Authorization", bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.detailReady").value(true))
			.andExpect(jsonPath("$.data.steps.length()").value(6));

		verify(llm, times(1)).enrichRecipeDetail(any());
		assertThat(jdbc.queryForObject("select detail_status from recipes where id = ?", String.class, recipeId))
			.isEqualTo("DETAILED");
	}

	@Test
	void fallsBackToBasicDetailWhenLlmFails() throws Exception {
		when(llm.enrichRecipeDetail(any())).thenThrow(new RuntimeException("LLM 호출 실패"));

		mockMvc.perform(get("/api/me/recipes/{id}", recipeId).header("Authorization", bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.detailReady").value(false))
			.andExpect(jsonPath("$.data.steps.length()").value(2))
			.andExpect(jsonPath("$.data.seasonings.length()").value(0));

		assertThat(jdbc.queryForObject("select detail_status from recipes where id = ?", String.class, recipeId))
			.isEqualTo("BASIC");
	}

	@Test
	void fallsBackToBasicDetailWhenValidationFails() throws Exception {
		var invalid = new LlmRecipeDetailResponse(
			List.of(new IngredientAmount(999L, "2개")),
			List.of(),
			List.of("a", "b", "c", "d", "e", "f"));
		when(llm.enrichRecipeDetail(any())).thenReturn(invalid);

		mockMvc.perform(get("/api/me/recipes/{id}", recipeId).header("Authorization", bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.detailReady").value(false))
			.andExpect(jsonPath("$.data.steps.length()").value(2));

		verify(llm, times(1)).enrichRecipeDetail(any());
		assertThat(jdbc.queryForObject("select detail_status from recipes where id = ?", String.class, recipeId))
			.isEqualTo("BASIC");
	}

	@Test
	void claimDetailGenerationOnlySucceedsOnce() {
		Instant now = Instant.now();
		Instant retryCooldownBefore = now.minusSeconds(30);
		Instant staleBefore = now.minusSeconds(120);

		assertThat(recipeRepository.claimDetailGeneration(recipeId, now, retryCooldownBefore, staleBefore)).isEqualTo(1);
		assertThat(recipeRepository.claimDetailGeneration(recipeId, now, retryCooldownBefore, staleBefore)).isEqualTo(0);
	}

	@Test
	void staleGenerationCanBeReclaimed() {
		Instant longAgo = Instant.now().minusSeconds(300);
		assertThat(recipeRepository.claimDetailGeneration(recipeId, longAgo, longAgo.minusSeconds(30), longAgo.minusSeconds(1)))
			.isEqualTo(1);

		Instant now = Instant.now();
		Instant retryCooldownBefore = now.minusSeconds(30);
		Instant staleBefore = now.minusSeconds(120);
		assertThat(recipeRepository.claimDetailGeneration(recipeId, now, retryCooldownBefore, staleBefore)).isEqualTo(1);
	}

	@Test
	void retryCooldownBlocksImmediateRetryAfterFailureButAllowsItLater() {
		Instant firstAttempt = Instant.now();
		assertThat(recipeRepository.claimDetailGeneration(recipeId, firstAttempt,
			firstAttempt.minusSeconds(30), firstAttempt.minusSeconds(120))).isEqualTo(1);

		// 실패 후 BASIC으로 복귀(실제 resetToBasic과 동일하게 started_at은 그대로 둠)
		jdbc.update("update recipes set detail_status = 'BASIC' where id = ?", recipeId);

		// 쿨다운이 아직 안 지남: retryCooldownBefore가 firstAttempt보다 이전이면 재선점 실패
		assertThat(recipeRepository.claimDetailGeneration(recipeId, firstAttempt.plusSeconds(1),
			firstAttempt.minusSeconds(5), firstAttempt.plusSeconds(120))).isEqualTo(0);

		// 쿨다운이 지남: retryCooldownBefore가 firstAttempt보다 이후면 재선점 성공
		assertThat(recipeRepository.claimDetailGeneration(recipeId, firstAttempt.plusSeconds(31),
			firstAttempt.plusSeconds(1), firstAttempt.plusSeconds(150))).isEqualTo(1);
	}

	private String bearer() {
		return "Bearer " + tokenService.issue(42L).accessToken();
	}
}
