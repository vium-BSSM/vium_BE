package com.vium.post.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.auth.service.TokenService;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:post-domain-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Transactional
@Sql(scripts = "/db/inventory-query-support.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class PostPreviewControllerIntegrationTest {

	private static final Long USER_ID = 42L;
	private static final Long OTHER_USER_ID = 43L;
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TokenService tokenService;

	private long nextInventoryItemId = 1;
	private long nextCatalogId = 1;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("insert into units (id,code,name) values (1,'ea','개'),(2,'g','그램'),(3,'kg','킬로그램')");
		jdbcTemplate.update("insert into storage_methods (id,code,name) values (1,'cold','냉장'),(2,'frozen','냉동'),(3,'room','실온')");
		jdbcTemplate.update(
			"insert into item_statuses (id,code,name) values (1,'active','보유중'),(2,'consumed','소진'),(3,'disposed','폐기'),(4,'shared','나눔·판매')");
		jdbcTemplate.update("insert into event_sources (id,code,name) values (1,'manual','수동')");
		jdbcTemplate.update("delete from ingredient_categories");
		jdbcTemplate.update("insert into ingredient_categories (id,name) values (1,'유제품')");
	}

	private Long insertCatalog(boolean postAllowed, boolean withCategory) {
		long id = nextCatalogId++;
		jdbcTemplate.update("""
			insert into ingredient_catalog (id, category_id, default_unit_id, name, post_allowed, created_at)
			values (?, ?, 3, '테스트재료', ?, CURRENT_TIMESTAMP)
			""", id, withCategory ? 1 : null, postAllowed);
		return id;
	}

	private void insertBaseValue(short categoryId, short unitId, String baseValue) {
		jdbcTemplate.update(
			"insert into credit_base_values (category_id, unit_id, base_value) values (?, ?, ?)",
			categoryId, unitId, new java.math.BigDecimal(baseValue));
	}

	private Long insertInventoryItem(Long catalogId, String customName, String statusCode, String quantity,
			String remainingQuantity, Short unitId, LocalDate expiresOn) {
		long id = nextInventoryItemId++;
		jdbcTemplate.update("""
			insert into inventory_items (id,user_id,ingredient_catalog_id,custom_name,status_id,unit_id,
			storage_method_id,initial_quantity,remaining_quantity,expires_on,created_at,updated_at)
			values (?,?,?,?,(select id from item_statuses where code = ?),?,1,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
			""", id, USER_ID, catalogId, customName, statusCode, unitId,
			new java.math.BigDecimal(quantity), new java.math.BigDecimal(remainingQuantity), expiresOn);
		return id;
	}

	private void insertOpenPost(Long inventoryItemId) {
		jdbcTemplate.update("""
			insert into posts (author_id, inventory_item_id, type, status, title, description, price,
			is_opened, latitude, longitude, like_count, created_at, updated_at)
			values (?, ?, 'SHARE', 'OPEN', '제목', '', 0, false, 37.5, 127.0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""", USER_ID, inventoryItemId);
	}

	private String bearer(Long userId) {
		return "Bearer " + tokenService.issue(userId).accessToken();
	}

	private LocalDate today() {
		return LocalDate.now(SEOUL);
	}

	@Test
	void returns404ForMissingIngredient() throws Exception {
		mockMvc.perform(get("/api/me/ingredients/999999/post-preview").header("Authorization", bearer(USER_ID)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void returns404ForOthersIngredient() throws Exception {
		Long catalogId = insertCatalog(true, true);
		Long itemId = insertInventoryItem(catalogId, null, "active", "1", "1", (short) 3, today().plusDays(10));

		mockMvc.perform(get("/api/me/ingredients/" + itemId + "/post-preview").header("Authorization", bearer(OTHER_USER_ID)))
			.andExpect(status().isNotFound());
	}

	@Test
	void reportsAlreadyUsed() throws Exception {
		Long catalogId = insertCatalog(true, true);
		Long itemId = insertInventoryItem(catalogId, null, "active", "2", "1", (short) 3, today().plusDays(10));

		mockMvc.perform(get("/api/me/ingredients/" + itemId + "/post-preview").header("Authorization", bearer(USER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.eligible").value(false))
			.andExpect(jsonPath("$.data.ineligibleReasons[0]").value("ALREADY_USED"));
	}

	@Test
	void reportsNotActive() throws Exception {
		Long catalogId = insertCatalog(true, true);
		Long itemId = insertInventoryItem(catalogId, null, "consumed", "1", "1", (short) 3, today().plusDays(10));

		mockMvc.perform(get("/api/me/ingredients/" + itemId + "/post-preview").header("Authorization", bearer(USER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.eligible").value(false))
			.andExpect(jsonPath("$.data.ineligibleReasons[0]").value("NOT_ACTIVE"));
	}

	@Test
	void reportsAlreadyPosted() throws Exception {
		Long catalogId = insertCatalog(true, true);
		Long itemId = insertInventoryItem(catalogId, null, "active", "1", "1", (short) 3, today().plusDays(10));
		insertOpenPost(itemId);

		mockMvc.perform(get("/api/me/ingredients/" + itemId + "/post-preview").header("Authorization", bearer(USER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.eligible").value(false))
			.andExpect(jsonPath("$.data.ineligibleReasons[0]").value("ALREADY_POSTED"));
	}

	@Test
	void reportsExpiresTooSoonWhenLessThanOneDayLeft() throws Exception {
		Long catalogId = insertCatalog(true, true);
		Long itemId = insertInventoryItem(catalogId, null, "active", "1", "1", (short) 3, today());

		mockMvc.perform(get("/api/me/ingredients/" + itemId + "/post-preview").header("Authorization", bearer(USER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.eligible").value(false))
			.andExpect(jsonPath("$.data.ineligibleReasons[0]").value("EXPIRES_TOO_SOON"));
	}

	@Test
	void reportsExpiresTooSoonWhenExpiresOnIsNull() throws Exception {
		Long catalogId = insertCatalog(true, true);
		Long itemId = insertInventoryItem(catalogId, null, "active", "1", "1", (short) 3, null);

		mockMvc.perform(get("/api/me/ingredients/" + itemId + "/post-preview").header("Authorization", bearer(USER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.eligible").value(false))
			.andExpect(jsonPath("$.data.ineligibleReasons[0]").value("EXPIRES_TOO_SOON"));
	}

	@Test
	void reportsNotInCatalog() throws Exception {
		Long itemId = insertInventoryItem(null, "커스텀재료", "active", "1", "1", (short) 3, today().plusDays(10));

		mockMvc.perform(get("/api/me/ingredients/" + itemId + "/post-preview").header("Authorization", bearer(USER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.eligible").value(false))
			.andExpect(jsonPath("$.data.ineligibleReasons[0]").value("NOT_IN_CATALOG"));
	}

	@Test
	void reportsProhibitedItem() throws Exception {
		Long catalogId = insertCatalog(false, true);
		Long itemId = insertInventoryItem(catalogId, null, "active", "1", "1", (short) 3, today().plusDays(10));

		mockMvc.perform(get("/api/me/ingredients/" + itemId + "/post-preview").header("Authorization", bearer(USER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.eligible").value(false))
			.andExpect(jsonPath("$.data.ineligibleReasons[0]").value("PROHIBITED_ITEM"));
	}

	@Test
	void saleBlockedWithNoPriceBasisWhenNoBaseValueRow() throws Exception {
		Long catalogId = insertCatalog(true, true);
		Long itemId = insertInventoryItem(catalogId, null, "active", "1", "1", (short) 3, today().plusDays(10));

		mockMvc.perform(get("/api/me/ingredients/" + itemId + "/post-preview").header("Authorization", bearer(USER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.eligible").value(true))
			.andExpect(jsonPath("$.data.saleAllowed").value(false))
			.andExpect(jsonPath("$.data.saleBlockedReason").value("NO_PRICE_BASIS"))
			.andExpect(jsonPath("$.data.creditEstimate").doesNotExist());
	}

	@Test
	void saleBlockedWithZeroPriceWhenBaseValueRoundsToZero() throws Exception {
		Long catalogId = insertCatalog(true, true);
		insertBaseValue((short) 1, (short) 3, "0.001");
		Long itemId = insertInventoryItem(catalogId, null, "active", "0.001", "0.001", (short) 3, today().plusDays(10));

		mockMvc.perform(get("/api/me/ingredients/" + itemId + "/post-preview").header("Authorization", bearer(USER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.eligible").value(true))
			.andExpect(jsonPath("$.data.saleAllowed").value(false))
			.andExpect(jsonPath("$.data.saleBlockedReason").value("ZERO_PRICE"));
	}

	@Test
	void saleAllowedWithFullFreshnessFactorAndComputedRange() throws Exception {
		Long catalogId = insertCatalog(true, true);
		insertBaseValue((short) 1, (short) 3, "1000.000");
		// 2kg at 1000/kg base, D>=7 => freshness 1.0 => recommendedPrice=2000
		Long itemId = insertInventoryItem(catalogId, null, "active", "2", "2", (short) 3, today().plusDays(7));

		mockMvc.perform(get("/api/me/ingredients/" + itemId + "/post-preview").header("Authorization", bearer(USER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.eligible").value(true))
			.andExpect(jsonPath("$.data.saleAllowed").value(true))
			.andExpect(jsonPath("$.data.saleBlockedReason").doesNotExist())
			.andExpect(jsonPath("$.data.creditEstimate.recommendedPrice").value(2000))
			.andExpect(jsonPath("$.data.creditEstimate.minPrice").value(1600))
			.andExpect(jsonPath("$.data.creditEstimate.maxPrice").value(2400))
			.andExpect(jsonPath("$.data.creditEstimate.categoryBaseValue").value(1000.0))
			.andExpect(jsonPath("$.data.creditEstimate.freshnessFactor").value(1.0));
	}

	@Test
	void freshnessFactorAppliesForFourToSixDaysRemaining() throws Exception {
		Long catalogId = insertCatalog(true, true);
		insertBaseValue((short) 1, (short) 3, "1000.000");
		// 1kg at 1000/kg, D=5 => freshness 0.9 => recommendedPrice=900
		Long itemId = insertInventoryItem(catalogId, null, "active", "1", "1", (short) 3, today().plusDays(5));

		mockMvc.perform(get("/api/me/ingredients/" + itemId + "/post-preview").header("Authorization", bearer(USER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.creditEstimate.recommendedPrice").value(900))
			.andExpect(jsonPath("$.data.creditEstimate.freshnessFactor").value(0.9));
	}

	@Test
	void freshnessFactorAppliesForOneDayRemaining() throws Exception {
		Long catalogId = insertCatalog(true, true);
		insertBaseValue((short) 1, (short) 3, "1000.000");
		// 1kg at 1000/kg, D=1 => freshness 0.6 => recommendedPrice=600
		Long itemId = insertInventoryItem(catalogId, null, "active", "1", "1", (short) 3, today().plusDays(1));

		mockMvc.perform(get("/api/me/ingredients/" + itemId + "/post-preview").header("Authorization", bearer(USER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.creditEstimate.recommendedPrice").value(600))
			.andExpect(jsonPath("$.data.creditEstimate.freshnessFactor").value(0.6));
	}

	@Test
	void convertsGramsToKilogramBaseUnitForPricing() throws Exception {
		Long catalogId = insertCatalog(true, true);
		insertBaseValue((short) 1, (short) 3, "1000.000");
		// 500g => 0.5kg at 1000/kg, D>=7 => freshness 1.0 => recommendedPrice=500
		Long itemId = insertInventoryItem(catalogId, null, "active", "500", "500", (short) 2, today().plusDays(10));

		mockMvc.perform(get("/api/me/ingredients/" + itemId + "/post-preview").header("Authorization", bearer(USER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.saleAllowed").value(true))
			.andExpect(jsonPath("$.data.creditEstimate.recommendedPrice").value(500));
	}
}
