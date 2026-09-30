package com.vium.recipe.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.auth.service.TokenService;
import com.vium.recipe.client.ImageSearchClient;
import com.vium.recipe.client.LlmRecipeClient;
import com.vium.recipe.dto.GeneratedRecipe;
import com.vium.recipe.dto.LlmRecipeResponse;
import com.vium.recipe.dto.LlmRecipeResponse.RecipeDto;
import com.vium.recipe.entity.RecipeCategory;
import com.vium.recipe.repository.RecipeRepository;
import com.vium.recipe.repository.RecipeQueryRepository;
import com.vium.recipe.service.RecipeRecommendationWriter;
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
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:recipe-generation;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class RecipeGenerationIntegrationTest {
	@Autowired private MockMvc mockMvc;
	@Autowired private TokenService tokenService;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private JsonMapper mapper;
	@Autowired private RecipeRecommendationWriter writer;
	@Autowired private RecipeRepository recipes;
	@Autowired private RecipeQueryRepository query;
	@MockitoBean private LlmRecipeClient llm;
	@MockitoBean private ImageSearchClient images;

	private final RecipeDto recipe = new RecipeDto("Soup", "KOREAN", 10,
		List.of(1), List.of("Prepare", "Cook"), "soup", "Use soon");

	@BeforeEach
	void setUp() {
		for (String table : List.of("recipe_suggestions", "recipe_steps", "recipe_ingredients", "recipes",
			"inventory_items", "ingredient_catalog", "units", "item_statuses")) {
			jdbc.update("delete from " + table);
		}
		jdbc.update("insert into units (id,code,name) values (1,'ea','ea')");
		jdbc.update("insert into item_statuses (id,code,name) values (1,'active','active')");
		jdbc.update("insert into ingredient_catalog (id,name,default_unit_id,created_at) values (1,'Egg',1,CURRENT_TIMESTAMP)");
		jdbc.update("""
			insert into inventory_items (user_id,ingredient_catalog_id,initial_quantity,remaining_quantity,
			unit_id,status_id,created_at,updated_at) values (42,1,2,2,1,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
			""");
		when(llm.generateRecipes(any())).thenReturn(new LlmRecipeResponse(List.of(recipe)));
	}

	@Test
	void generatesRecommendationAndPersistsDetail() throws Exception {
		var response = mockMvc.perform(get("/api/me/recipes/recommended").header("Authorization", bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.recipes.length()").value(1))
			.andExpect(jsonPath("$.data.recipes[0].usedIngredients[0].ingredientCatalogId").value(1))
			.andReturn().getResponse().getContentAsString();
		long id = mapper.readTree(response).path("data").path("recipes").get(0).path("recipeId").asLong();
		mockMvc.perform(get("/api/me/recipes/{id}", id).header("Authorization", bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.ingredients[0].name").value("Egg"))
			.andExpect(jsonPath("$.data.steps.length()").value(2))
			.andExpect(jsonPath("$.data.steps[0].step").value(1))
			.andExpect(jsonPath("$.data.steps[0].description").value("Prepare"))
			.andExpect(jsonPath("$.data.steps[1].step").value(2))
			.andExpect(jsonPath("$.data.steps[1].description").value("Cook"));
		assertThat(jdbc.queryForObject("select reason from recipe_suggestions where recipe_id = ?", String.class, id))
			.isEqualTo("Use soon");
		assertThat(query.findReusableRecipes(RecipeCategory.KOREAN, List.of(1L)))
			.extracting(r -> r.getId()).containsExactly(id);
		assertThat(query.findReusableRecipes(RecipeCategory.KOREAN, List.of(2L))).isEmpty();
	}

	@Test
	void incompleteCachedRecommendationIsRegenerated() throws Exception {
		mockMvc.perform(get("/api/me/recipes/recommended").header("Authorization", bearer()))
			.andExpect(status().isOk());
		long oldId = recipes.findAll().getFirst().getId();
		jdbc.update("delete from recipe_ingredients where recipe_id = ?", oldId);
		jdbc.update("delete from recipe_steps where recipe_id = ?", oldId);
		mockMvc.perform(get("/api/me/recipes/recommended").header("Authorization", bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.recipes.length()").value(1))
			.andExpect(jsonPath("$.data.recipes[0].usedIngredients.length()").value(1));
		verify(llm, times(2)).generateRecipes(any());
		assertThat(query.findReusableRecipes(RecipeCategory.KOREAN, List.of(1L)))
			.hasSize(1).allSatisfy(r -> assertThat(r.getId()).isNotEqualTo(oldId));
	}

	@Test
	void completeCachedRecommendationDoesNotCallLlmAgain() throws Exception {
		for (int i = 0; i < 2; i++) {
			mockMvc.perform(get("/api/me/recipes/recommended").header("Authorization", bearer()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.recipes.length()").value(1));
		}
		verify(llm).generateRecipes(any());
	}

	@Test
	void excludesRecipesWithoutIngredientsOrSteps() {
		writer.saveRecommendations(42L, List.of(), List.of(new GeneratedRecipe(recipe, null)),
			UUID.randomUUID(), "hash", LocalDateTime.now());
		jdbc.update("delete from recipe_steps");
		assertThat(query.findReusableRecipes(RecipeCategory.KOREAN, List.of(1L))).isEmpty();
		jdbc.update("insert into recipe_steps (recipe_id,step_order,description) select id,1,'Cook' from recipes");
		jdbc.update("delete from recipe_ingredients");
		assertThat(query.findReusableRecipes(RecipeCategory.KOREAN, List.of(1L))).isEmpty();
	}

	@Test
	void rollsBackEntireBatchWhenDetailSaveFails() {
		var invalid = new RecipeDto("Bad", "KOREAN", 10, List.of(1), List.of("x".repeat(501)), "soup", null);
		assertThatThrownBy(() -> writer.saveRecommendations(42L, List.of(),
			List.of(new GeneratedRecipe(recipe, null), new GeneratedRecipe(invalid, null)),
			UUID.randomUUID(), "hash", LocalDateTime.now())).isInstanceOf(RuntimeException.class);
		for (String table : List.of("recipes", "recipe_ingredients", "recipe_steps", "recipe_suggestions")) {
			assertThat(jdbc.queryForObject("select count(*) from " + table, Integer.class)).isZero();
		}
	}

	@Test
	void reusesRecipeWithoutDuplicatingDetails() {
		writer.saveRecommendations(42L, List.of(), List.of(new GeneratedRecipe(recipe, null)),
			UUID.randomUUID(), "hash", LocalDateTime.now());
		writer.saveRecommendations(43L, recipes.findAll(), List.of(), UUID.randomUUID(), "other", LocalDateTime.now());
		assertThat(jdbc.queryForObject("select count(*) from recipes", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from recipe_ingredients", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from recipe_steps", Integer.class)).isEqualTo(2);
		assertThat(jdbc.queryForObject("select count(*) from recipe_suggestions", Integer.class)).isEqualTo(2);
	}

	@Test
	void invalidRecipesReturn503WithoutPartialData() throws Exception {
		when(llm.generateRecipes(any())).thenReturn(new LlmRecipeResponse(List.of(
			new RecipeDto("Invalid", "KOREAN", 10, List.of(999), List.of("Cook"), "soup", null))));
		mockMvc.perform(get("/api/me/recipes/recommended").header("Authorization", bearer()))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.error.code").value("RECIPE_GENERATION_FAILED"));
		assertThat(recipes.count()).isZero();
	}

	private String bearer() {
		return "Bearer " + tokenService.issue(42L).accessToken();
	}
}
