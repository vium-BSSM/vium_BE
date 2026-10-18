package com.vium.shopping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.auth.service.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ShoppingListIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TokenService tokenService;

	private final Long userId = 42L;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("delete from \"shopping_list_items\"");
		jdbcTemplate.update("delete from \"units\"");
		jdbcTemplate.update("insert into \"units\" (\"id\", \"code\", \"name\") values (4, 'ml', 'ml')");
	}

	@Test
	void getShoppingListIncludesUnitName() throws Exception {
		jdbcTemplate.update("""
			insert into "shopping_list_items"
			("id", "user_id", "custom_name", "suggested_quantity", "unit_id", "reason", "is_checked", "created_at")
			values (1, ?, '우유', 500, 4, '지난달 절반을 버림', false, CURRENT_TIMESTAMP)
			""", userId);

		mockMvc.perform(get("/api/me/shopping-list-items")
				.header("Authorization", "Bearer " + tokenService.issue(userId).accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].unitId").value(4))
			.andExpect(jsonPath("$.data.items[0].unitName").value("ml"));
	}

	@Test
	void addShoppingListItemResponseIncludesUnitName() throws Exception {
		mockMvc.perform(post("/api/me/shopping-list-items")
				.header("Authorization", "Bearer " + tokenService.issue(userId).accessToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"customName":"계란","suggestedQuantity":10,"unitId":4,"reason":"자주 버림"}
					"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.unitId").value(4))
			.andExpect(jsonPath("$.data.unitName").value("ml"));
	}

	@Test
	void updateCheckStatusResponseIncludesUnitName() throws Exception {
		jdbcTemplate.update("""
			insert into "shopping_list_items"
			("id", "user_id", "custom_name", "suggested_quantity", "unit_id", "reason", "is_checked", "created_at")
			values (2, ?, '당근', 300, 4, '테스트', false, CURRENT_TIMESTAMP)
			""", userId);

		mockMvc.perform(patch("/api/me/shopping-list-items/2")
				.header("Authorization", "Bearer " + tokenService.issue(userId).accessToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"isChecked\":true}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.unitName").value("ml"));
	}

	@Test
	void getShoppingListAllowsNullUnitWithoutError() throws Exception {
		jdbcTemplate.update("""
			insert into "shopping_list_items"
			("id", "user_id", "custom_name", "suggested_quantity", "unit_id", "reason", "is_checked", "created_at")
			values (3, ?, '소금', null, null, '단위 없음', false, CURRENT_TIMESTAMP)
			""", userId);

		mockMvc.perform(get("/api/me/shopping-list-items")
				.header("Authorization", "Bearer " + tokenService.issue(userId).accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[0].unitId").value(org.hamcrest.Matchers.nullValue()))
			.andExpect(jsonPath("$.data.items[0].unitName").value(org.hamcrest.Matchers.nullValue()));
	}
}
