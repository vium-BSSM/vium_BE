package com.vium.inventory.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.inventory.repository.InventoryItemRepository;
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
class InventoryControllerIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private InventoryItemRepository inventoryItemRepository;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("INSERT INTO units (id, code, name) VALUES (1, 'ea', '개')");
		jdbcTemplate.update("INSERT INTO storage_methods (id, code, name) VALUES (1, 'cold', '냉장')");
		jdbcTemplate.update("INSERT INTO item_statuses (id, code, name) VALUES (1, 'active', '보유중')");
		jdbcTemplate.update(
			"INSERT INTO ingredient_catalog (id, default_unit_id, name, created_at) VALUES (1, 1, '우유', CURRENT_TIMESTAMP)");
		jdbcTemplate.update("""
			INSERT INTO expiry_estimation_rules
				(id, ingredient_catalog_id, storage_method_id, shelf_life_days, source, effective_from)
			VALUES (1, 1, 1, 7, 'test', DATE '2026-01-01')
			""");
	}

	@Test
	void register_estimatesExpiryAndStoresInventory() throws Exception {
		mockMvc.perform(post("/api/me/ingredients")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "ingredientCatalogId": 1,
					  "quantity": 2.5,
					  "unitId": 1,
					  "storageMethodId": 1,
					  "purchasedOn": "2026-09-01",
					  "amount": 4500
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.ingredientCatalogId").value(1))
			.andExpect(jsonPath("$.data.initialQuantity").value(2.5))
			.andExpect(jsonPath("$.data.remainingQuantity").value(2.5))
			.andExpect(jsonPath("$.data.statusCode").value("active"))
			.andExpect(jsonPath("$.data.purchasedOn").value("2026-09-01"))
			.andExpect(jsonPath("$.data.expiresOn").value("2026-09-08"))
			.andExpect(jsonPath("$.error").value(nullValue()));

		org.assertj.core.api.Assertions.assertThat(inventoryItemRepository.count()).isEqualTo(1);
	}

	@Test
	void register_rejectsZeroQuantity() throws Exception {
		mockMvc.perform(post("/api/me/ingredients")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "customName": "두부",
					  "quantity": 0,
					  "unitId": 1,
					  "storageMethodId": 1,
					  "expiresOn": "2026-09-12"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	void register_rejectsExpiryBeforePurchase() throws Exception {
		mockMvc.perform(post("/api/me/ingredients")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "customName": "두부",
					  "quantity": 1,
					  "unitId": 1,
					  "storageMethodId": 1,
					  "purchasedOn": "2026-09-05",
					  "expiresOn": "2026-09-04"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	void register_requiresExpiryWhenItCannotBeEstimated() throws Exception {
		mockMvc.perform(post("/api/me/ingredients")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "customName": "직접 만든 반찬",
					  "quantity": 1,
					  "unitId": 1,
					  "storageMethodId": 1
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.error.message").value("소비기한을 추정할 수 없습니다. expiresOn을 입력해 주세요"));
	}

	@Test
	void register_acceptsCustomIngredientWithExplicitExpiry() throws Exception {
		mockMvc.perform(post("/api/me/ingredients")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "customName": "  직접 만든 반찬  ",
					  "quantity": 1,
					  "unitId": 1,
					  "purchasedOn": "2026-09-10",
					  "expiresOn": "2026-09-12"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.ingredientCatalogId").value(nullValue()))
			.andExpect(jsonPath("$.data.customName").value("직접 만든 반찬"))
			.andExpect(jsonPath("$.data.storageMethodId").value(nullValue()))
			.andExpect(jsonPath("$.data.expiresOn").value("2026-09-12"));
	}
}
