package com.vium.recipe.integration;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.auth.service.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RecipeRecommendationCatalogIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TokenService tokenService;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("INSERT INTO units (id, code, name) VALUES (1, 'ea', '개')");
		jdbcTemplate.update("INSERT INTO units (id, code, name) VALUES (2, 'g', '그램')");
		jdbcTemplate.update("INSERT INTO storage_methods (id, code, name) VALUES (1, 'cold', '냉장')");
		jdbcTemplate.update("INSERT INTO item_statuses (id, code, name) VALUES (1, 'active', '보유중')");
		jdbcTemplate.update(
			"INSERT INTO ingredient_catalog (id, default_unit_id, name, created_at) VALUES (1, 1, '우유', CURRENT_TIMESTAMP)");
		jdbcTemplate.update(
			"INSERT INTO ingredient_catalog (id, default_unit_id, name, created_at) VALUES (2, 2, '계란', CURRENT_TIMESTAMP)");
		jdbcTemplate.update("""
			INSERT INTO expiry_estimation_rules
				(id, ingredient_catalog_id, storage_method_id, shelf_life_days, source, effective_from)
			VALUES (1, 1, 1, 7, 'test', DATE '2026-01-01')
			""");
		jdbcTemplate.update("""
			INSERT INTO expiry_estimation_rules
				(id, ingredient_catalog_id, storage_method_id, shelf_life_days, source, effective_from)
			VALUES (2, 2, 1, 10, 'test', DATE '2026-01-01')
			""");
	}

	@Test
	void registerWithCatalogId_usesCustomNameFromCatalogWhenNotProvided() throws Exception {
		mockMvc.perform(post("/api/me/ingredients")
				.header("Authorization", "Bearer " + tokenService.issue(1L).accessToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "ingredientCatalogId": 1,
					  "quantity": 2.5,
					  "unitId": 1,
					  "storageMethodId": 1,
					  "purchasedOn": "2026-09-01"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.ingredientCatalogId").value(1))
			.andExpect(jsonPath("$.data.customName").value("우유"))
			.andExpect(jsonPath("$.error").value(nullValue()));
	}

	@Test
	void registerWithCatalogId_rejectsNonExistentCatalogId() throws Exception {
		mockMvc.perform(post("/api/me/ingredients")
				.header("Authorization", "Bearer " + tokenService.issue(1L).accessToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "ingredientCatalogId": 9999,
					  "quantity": 2.5,
					  "unitId": 1,
					  "storageMethodId": 1,
					  "purchasedOn": "2026-09-01"
					}
					"""))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false));
	}

	@Test
	void searchCatalog_returnsMatchingIngredients() throws Exception {
		mockMvc.perform(get("/api/ingredient-catalogs")
				.header("Authorization", "Bearer " + tokenService.issue(1L).accessToken())
				.param("keyword", "우유"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.items[0].name").value("우유"))
			.andExpect(jsonPath("$.data.items[0].ingredientCatalogId").value(1))
			.andExpect(jsonPath("$.error").value(nullValue()));
	}

	@Test
	void searchCatalog_returnsEmptyWhenNoMatch() throws Exception {
		mockMvc.perform(get("/api/ingredient-catalogs")
				.header("Authorization", "Bearer " + tokenService.issue(1L).accessToken())
				.param("keyword", "없는재료"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.items.length()").value(0))
			.andExpect(jsonPath("$.error").value(nullValue()));
	}
}
