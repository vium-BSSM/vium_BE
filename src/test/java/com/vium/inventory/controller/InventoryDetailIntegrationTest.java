package com.vium.inventory.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.auth.service.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Sql(scripts = "/db/inventory-query-support.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class InventoryDetailIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TokenService tokenService;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("insert into units (id, code, name) values (1,'ea','개')");
		jdbcTemplate.update("insert into storage_methods (id, code, name) values (1,'cold','냉장')");
		jdbcTemplate.update("insert into item_statuses (id, code, name) values (1,'active','보유중')");
		jdbcTemplate.update("""
			insert into inventory_items
			(id,user_id,custom_name,status_id,unit_id,storage_method_id,initial_quantity,remaining_quantity,
			 amount,purchased_on,expires_on,created_at,updated_at)
			values (100,1,'우유',1,1,1,2,1,2500,DATE '2026-01-01',DATE '2026-01-10',
			 TIMESTAMP '2026-01-01 00:00:00',TIMESTAMP '2026-01-01 00:00:00')
			""");
		jdbcTemplate.update("""
			insert into inventory_items
			(id,user_id,custom_name,status_id,unit_id,initial_quantity,remaining_quantity,
			 created_at,updated_at)
			values (200,2,'다른 사용자 재료',1,1,1,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
			""");
	}

	@Test
	void returnsFullDetailIncludingStorageMethodName() throws Exception {
		mockMvc.perform(authenticatedGet("/api/me/ingredients/100"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.error").value(nullValue()))
			.andExpect(jsonPath("$.data.inventoryItemId").value(100))
			.andExpect(jsonPath("$.data.ingredientCatalogId").value(nullValue()))
			.andExpect(jsonPath("$.data.name").value("우유"))
			.andExpect(jsonPath("$.data.categoryName").value(nullValue()))
			.andExpect(jsonPath("$.data.initialQuantity").value(2))
			.andExpect(jsonPath("$.data.remainingQuantity").value(1))
			.andExpect(jsonPath("$.data.amount").value(2500))
			.andExpect(jsonPath("$.data.unitId").value(1))
			.andExpect(jsonPath("$.data.unit").value("개"))
			.andExpect(jsonPath("$.data.storageMethodId").value(1))
			.andExpect(jsonPath("$.data.storageMethodName").value("냉장"))
			.andExpect(jsonPath("$.data.statusCode").value("active"))
			.andExpect(jsonPath("$.data.purchasedOn").value("2026-01-01"))
			.andExpect(jsonPath("$.data.expiresOn").value("2026-01-10"));
	}

	@Test
	void returnsNullStorageMethodFieldsWhenNotSet() throws Exception {
		mockMvc.perform(get("/api/me/ingredients/200")
				.header("Authorization", "Bearer " + tokenService.issue(2L).accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.name").value("다른 사용자 재료"))
			.andExpect(jsonPath("$.data.storageMethodId").value(nullValue()))
			.andExpect(jsonPath("$.data.storageMethodName").value(nullValue()));
	}

	@Test
	void returnsNotFoundForMissingIngredient() throws Exception {
		mockMvc.perform(authenticatedGet("/api/me/ingredients/999"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void returnsNotFoundForOtherUsersIngredient() throws Exception {
		// 200은 userId=2 소유. userId=1로 조회하면 403이 아니라 404여야 한다(존재 여부 노출 방지).
		mockMvc.perform(authenticatedGet("/api/me/ingredients/200"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	private MockHttpServletRequestBuilder authenticatedGet(String url) {
		return get(url).header("Authorization", "Bearer " + tokenService.issue(1L).accessToken());
	}
}
