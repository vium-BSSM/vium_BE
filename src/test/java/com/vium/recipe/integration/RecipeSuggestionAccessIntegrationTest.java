package com.vium.recipe.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.auth.service.TokenService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:recipe-access;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Transactional
class RecipeSuggestionAccessIntegrationTest {
	@Autowired private MockMvc mvc;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private TokenService tokens;
	@Autowired private EntityManager entityManager;

	@BeforeEach
	void setUp() {
		jdbc.update("insert into units (id,code,name) values (1,'ea','개')");
		jdbc.update("insert into item_statuses (id,code,name) values (1,'active','보유중'),(2,'consumed','소진')");
		jdbc.update("insert into ingredient_catalog (id,name,default_unit_id,created_at) values (1,'감자',1,CURRENT_TIMESTAMP)");
		jdbc.update("""
			insert into recipes (id,title,category,cook_time,created_at)
			values (1,'감자 요리','KOREAN',10,CURRENT_TIMESTAMP),(2,'다른 요리','KOREAN',10,CURRENT_TIMESTAMP)
			""");
		jdbc.update("insert into recipe_ingredients (recipe_id,ingredient_catalog_id) values (1,1)");
		jdbc.update("insert into recipe_steps (recipe_id,step_order,description) values (1,1,'감자를 익힌다')");
		jdbc.update("""
			insert into inventory_items (id,user_id,ingredient_catalog_id,unit_id,status_id,
			initial_quantity,remaining_quantity,created_at,updated_at)
			values (1,42,1,1,1,4,4,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
			""");
	}

	@ParameterizedTest
	@ValueSource(ints = {1, 3})
	void detailAllowsAnyNumberOfOwnSuggestions(int count) throws Exception {
		addSuggestions(42, 1, count);
		mvc.perform(get("/api/me/recipes/1").header("Authorization", bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.recipeId").value(1))
			.andExpect(jsonPath("$.data.ingredients[0].name").value("감자"))
			.andExpect(jsonPath("$.data.steps[0].description").value("감자를 익힌다"));
		assertSuggestions(count);
	}

	@ParameterizedTest
	@ValueSource(ints = {1, 3})
	void completionAllowsAnyNumberOfOwnSuggestions(int count) throws Exception {
		addSuggestions(42, 1, count);
		mvc.perform(post("/api/me/recipes/1/complete").header("Authorization", bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"usages\":[{\"inventoryId\":1,\"usageRate\":50}]}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
		assertThat(remaining()).isEqualByComparingTo("2");
		assertSuggestions(count);
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 3})
	void detailRejectsMissingOrOtherUsersSuggestions(int otherCount) throws Exception {
		addSuggestions(43, 1, otherCount);
		addSuggestions(42, 2, 1);
		mvc.perform(get("/api/me/recipes/1").header("Authorization", bearer()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("RECIPE_NOT_FOUND"));
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 3})
	void completionRejectsMissingOrOtherUsersSuggestionsWithoutChangingInventory(int otherCount) throws Exception {
		addSuggestions(43, 1, otherCount);
		addSuggestions(42, 2, 1);
		mvc.perform(post("/api/me/recipes/1/complete").header("Authorization", bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"usages\":[{\"inventoryId\":1,\"usageRate\":50}]}"))
			.andExpect(status().isNotFound());
		assertThat(remaining()).isEqualByComparingTo("4");
	}

	private void addSuggestions(long userId, long recipeId, int count) {
		for (int i = 0; i < count; i++) {
			jdbc.update("""
				insert into recipe_suggestions (user_id,recipe_id,batch_id,inventory_hash,suggested_at)
				values (?,?,?,?,CURRENT_TIMESTAMP)
				""", userId, recipeId, UUID.randomUUID(), "test-inventory");
		}
	}

	private void assertSuggestions(int count) {
		assertThat(jdbc.queryForObject("select count(*) from recipe_suggestions", Integer.class)).isEqualTo(count);
	}

	private BigDecimal remaining() {
		entityManager.flush();
		return jdbc.queryForObject("select remaining_quantity from inventory_items where id=1", BigDecimal.class);
	}

	private String bearer() {
		return "Bearer " + tokens.issue(42L).accessToken();
	}
}
