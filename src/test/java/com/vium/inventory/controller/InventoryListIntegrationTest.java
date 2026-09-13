package com.vium.inventory.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Sql(scripts = "/db/inventory-query-support.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class InventoryListIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("""
			insert into users (id, email, display_name, created_at, updated_at)
			values (1, 'list@example.com', '조회 사용자', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
			       (2, 'other@example.com', '다른 사용자', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""");
		jdbcTemplate.update("insert into units (id, code, name) values (1, 'ea', '개')");
		jdbcTemplate.update("""
			insert into item_statuses (id, code, name)
			values (1, 'active', '보유중'), (2, 'consumed', '소진'), (3, 'disposed', '폐기')
			""");
		jdbcTemplate.update("insert into ingredient_categories (id, name) values (1, '유제품')");
		jdbcTemplate.update("""
			insert into ingredient_catalog (id, category_id, default_unit_id, name, created_at)
			values (1, 1, 1, '우유', CURRENT_TIMESTAMP), (2, null, 1, '미분류 재료', CURRENT_TIMESTAMP)
			""");
	}

	@Test
	void list_returnsOnlyCurrentUsersActiveIngredientsWithResponseFields() throws Exception {
		LocalDate expiry = LocalDate.now().plusDays(2);
		insertItem(10, 1, 1, 1L, null, expiry);
		insertItem(11, 2, 1, 1L, null, expiry);
		insertItem(12, 1, 2, 1L, null, expiry);
		insertItem(13, 1, 3, 1L, null, expiry);

		mockMvc.perform(get("/api/me/ingredients"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.error").value(nullValue()))
			.andExpect(jsonPath("$.data.ingredients.length()").value(1))
			.andExpect(jsonPath("$.data.ingredients[0].inventoryItemId").value(10))
			.andExpect(jsonPath("$.data.ingredients[0].ingredientCatalogId").value(1))
			.andExpect(jsonPath("$.data.ingredients[0].name").value("우유"))
			.andExpect(jsonPath("$.data.ingredients[0].categoryName").value("유제품"))
			.andExpect(jsonPath("$.data.ingredients[0].initialQuantity").value(2.5))
			.andExpect(jsonPath("$.data.ingredients[0].remainingQuantity").value(1.25))
			.andExpect(jsonPath("$.data.ingredients[0].unitId").value(1))
			.andExpect(jsonPath("$.data.ingredients[0].unit").value("개"))
			.andExpect(jsonPath("$.data.ingredients[0].statusCode").value("active"))
			.andExpect(jsonPath("$.data.ingredients[0].purchasedOn").value("2026-01-01"))
			.andExpect(jsonPath("$.data.ingredients[0].expiresOn").value(expiry.toString()));
	}

	@Test
	void list_ordersByExpiryThenIdAndPutsUnknownExpiryLast() throws Exception {
		LocalDate today = LocalDate.now();
		insertItem(10, 1, 1, 1L, null, null);
		insertItem(11, 1, 1, 1L, null, today.plusDays(10));
		insertItem(13, 1, 1, 1L, null, today);
		insertItem(12, 1, 1, 1L, null, today);
		insertItem(14, 1, 1, 1L, null, today.minusDays(1));

		mockMvc.perform(get("/api/me/ingredients").param("expiringSoon", "false"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.ingredients.length()").value(5))
			.andExpect(jsonPath("$.data.ingredients[0].inventoryItemId").value(14))
			.andExpect(jsonPath("$.data.ingredients[1].inventoryItemId").value(12))
			.andExpect(jsonPath("$.data.ingredients[2].inventoryItemId").value(13))
			.andExpect(jsonPath("$.data.ingredients[3].inventoryItemId").value(11))
			.andExpect(jsonPath("$.data.ingredients[4].inventoryItemId").value(10))
			.andExpect(jsonPath("$.data.ingredients[4].expiresOn").value(nullValue()));
	}

	@Test
	void list_keepsCustomAndUncategorizedIngredients() throws Exception {
		LocalDate expiry = LocalDate.now();
		insertItem(10, 1, 1, null, "직접 만든 반찬", expiry);
		insertItem(11, 1, 1, 2L, null, expiry);
		insertItem(12, 1, 1, 1L, "사용자 입력 이름", expiry);

		mockMvc.perform(get("/api/me/ingredients"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.ingredients.length()").value(3))
			.andExpect(jsonPath("$.data.ingredients[0].name").value("직접 만든 반찬"))
			.andExpect(jsonPath("$.data.ingredients[0].ingredientCatalogId").value(nullValue()))
			.andExpect(jsonPath("$.data.ingredients[0].categoryName").value(nullValue()))
			.andExpect(jsonPath("$.data.ingredients[1].name").value("미분류 재료"))
			.andExpect(jsonPath("$.data.ingredients[1].categoryName").value(nullValue()))
			.andExpect(jsonPath("$.data.ingredients[2].name").value("우유"));
	}

	@Test
	void list_returnsEmptyArrayWhenNoIngredientsMatch() throws Exception {
		mockMvc.perform(get("/api/me/ingredients"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.ingredients").isArray())
			.andExpect(jsonPath("$.data.ingredients").isEmpty());
		insertItem(10, 1, 1, 1L, null, LocalDate.now().plusDays(3));
		mockMvc.perform(get("/api/me/ingredients").param("expiringSoon", "true"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.ingredients").isEmpty());
	}

	@Test
	void list_expiringSoonIncludesOverdueTodayAndDefaultBoundaryOnly() throws Exception {
		LocalDate today = LocalDate.now();
		insertItem(10, 1, 1, 1L, null, today.minusDays(1));
		insertItem(11, 1, 1, 1L, null, today);
		insertItem(12, 1, 1, 1L, null, today.plusDays(2));
		insertItem(13, 1, 1, 1L, null, today.plusDays(3));
		insertItem(14, 1, 1, 1L, null, null);
		insertItem(15, 2, 1, 1L, null, today);
		insertItem(16, 1, 2, 1L, null, today);
		insertItem(17, 1, 3, 1L, null, today);

		mockMvc.perform(get("/api/me/ingredients").param("expiringSoon", "true"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.ingredients.length()").value(3))
			.andExpect(jsonPath("$.data.ingredients[0].inventoryItemId").value(10))
			.andExpect(jsonPath("$.data.ingredients[1].inventoryItemId").value(11))
			.andExpect(jsonPath("$.data.ingredients[2].inventoryItemId").value(12));
	}

	@Test
	void list_expiringSoonUsesCurrentUsersConfiguredDays() throws Exception {
		jdbcTemplate.update("update users set expiry_alert_days = 5 where id = 1");
		insertItem(10, 1, 1, 1L, null, LocalDate.now().plusDays(5));
		insertItem(11, 1, 1, 1L, null, LocalDate.now().plusDays(6));

		mockMvc.perform(get("/api/me/ingredients").param("expiringSoon", "true"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.ingredients.length()").value(1))
			.andExpect(jsonPath("$.data.ingredients[0].inventoryItemId").value(10));
	}

	@Test
	void list_rejectsInvalidBoolean() throws Exception {
		mockMvc.perform(get("/api/me/ingredients").param("expiringSoon", "invalid"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").value(nullValue()))
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	private void insertItem(long id, long userId, int statusId, Long catalogId, String customName,
			LocalDate expiresOn) {
		jdbcTemplate.update("""
			insert into inventory_items
			(id, user_id, ingredient_catalog_id, custom_name, status_id, unit_id,
			 initial_quantity, remaining_quantity, purchased_on, expires_on, created_at, updated_at)
			values (?, ?, ?, ?, ?, 1, 2.5, 1.25, DATE '2026-01-01', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""", id, userId, catalogId, customName, statusId, expiresOn);
	}
}
