package com.vium.recipe.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.auth.service.TokenService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * 레시피 상세의 재고 수량·단위 응답과 요리 완료의 usedQuantity 반영을 검증한다.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:recipe-quantity;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Transactional
class RecipeQuantityIntegrationTest {

	private static final long CARROT_INVENTORY_ID = 1L;      // 당근, 개, 남은 수량 4
	private static final long POTATO_INVENTORY_ID = 2L;      // 감자, g, 남은 수량 500
	private static final long OTHER_USER_INVENTORY_ID = 3L;  // 다른 사용자의 재고
	private static final long CONSUMED_INVENTORY_ID = 4L;    // 이미 소진된 재고

	@Autowired private MockMvc mvc;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private TokenService tokens;
	@Autowired private EntityManager entityManager;

	@BeforeEach
	void setUp() {
		jdbc.update("insert into units (id,code,name) values (1,'ea','개'),(2,'g','g')");
		jdbc.update("insert into item_statuses (id,code,name) values (1,'active','보유중'),(2,'consumed','소진'),(3,'disposed','폐기')");
		jdbc.update("insert into event_sources (id,code,name) values (1,'manual','수동')");
		jdbc.update("""
			insert into ingredient_catalog (id,name,default_unit_id,created_at)
			values (1,'당근',1,CURRENT_TIMESTAMP),(2,'감자',2,CURRENT_TIMESTAMP),(3,'소금',2,CURRENT_TIMESTAMP)
			""");
		jdbc.update("insert into recipes (id,title,category,cook_time,created_at) values (1,'당근 감자 볶음','KOREAN',10,CURRENT_TIMESTAMP)");
		jdbc.update("insert into recipe_ingredients (recipe_id,ingredient_catalog_id) values (1,1),(1,2),(1,3)");
		jdbc.update("insert into recipe_steps (recipe_id,step_order,description) values (1,1,'재료를 볶는다')");
		jdbc.update("""
			insert into recipe_suggestions (user_id,recipe_id,batch_id,inventory_hash,suggested_at)
			values (42,1,?,'test-inventory',CURRENT_TIMESTAMP)
			""", UUID.randomUUID());
		insertInventory(CARROT_INVENTORY_ID, 42, 1, 1, 1, "4", "4");
		insertInventory(POTATO_INVENTORY_ID, 42, 2, 2, 1, "500", "500");
		insertInventory(OTHER_USER_INVENTORY_ID, 43, 1, 1, 1, "4", "4");
		insertInventory(CONSUMED_INVENTORY_ID, 42, 3, 2, 2, "3", "0");
	}

	// ===== 레시피 상세: 남은 수량과 단위 =====

	@Test
	void detailReturnsRemainingQuantityAndUnitForOwnedIngredients() throws Exception {
		mvc.perform(get("/api/me/recipes/1").header("Authorization", bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.ingredients[0].name").value("당근"))
			.andExpect(jsonPath("$.data.ingredients[0].inventoryId").value(CARROT_INVENTORY_ID))
			.andExpect(jsonPath("$.data.ingredients[0].remainingQuantity").value(4))
			.andExpect(jsonPath("$.data.ingredients[0].unit").value("개"))
			.andExpect(jsonPath("$.data.ingredients[1].name").value("감자"))
			.andExpect(jsonPath("$.data.ingredients[1].inventoryId").value(POTATO_INVENTORY_ID))
			.andExpect(jsonPath("$.data.ingredients[1].remainingQuantity").value(500))
			.andExpect(jsonPath("$.data.ingredients[1].unit").value("g"));
	}

	@Test
	void detailReturnsNullQuantityAndUnitWhenInventoryIsMissing() throws Exception {
		// 소금은 재고가 없다
		mvc.perform(get("/api/me/recipes/1").header("Authorization", bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.ingredients[2].name").value("소금"))
			.andExpect(jsonPath("$.data.ingredients[2].inventoryId").value(nullValue()))
			.andExpect(jsonPath("$.data.ingredients[2].remainingQuantity").value(nullValue()))
			.andExpect(jsonPath("$.data.ingredients[2].unit").value(nullValue()));
	}

	@Test
	void detailQuantityReflectsCurrentRemainingQuantity() throws Exception {
		complete("""
			{"usages":[{"inventoryId":1,"usedQuantity":1.5}]}
			""").andExpect(status().isOk());
		entityManager.flush();

		mvc.perform(get("/api/me/recipes/1").header("Authorization", bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.ingredients[0].remainingQuantity").value(2.5));
	}

	// ===== 요리 완료: usedQuantity =====

	@Test
	void partialUsedQuantityDecreasesRemainingAndKeepsStatus() throws Exception {
		complete("""
			{"usages":[{"inventoryId":1,"usedQuantity":1.5},{"inventoryId":2,"usedQuantity":200}]}
			""")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));

		assertThat(remaining(CARROT_INVENTORY_ID)).isEqualByComparingTo("2.5");
		assertThat(remaining(POTATO_INVENTORY_ID)).isEqualByComparingTo("300");
		assertThat(statusId(CARROT_INVENTORY_ID)).isEqualTo(1);
		assertThat(statusId(POTATO_INVENTORY_ID)).isEqualTo(1);
	}

	@Test
	void partialUsageWithoutWasteStillRecordsConsumedEventAndKeepsActive() throws Exception {
		complete("""
			{"usages":[{"inventoryId":1,"usedQuantity":1.5,"wasteQuantity":0}]}
			""").andExpect(status().isOk());

		assertThat(remaining(CARROT_INVENTORY_ID)).isEqualByComparingTo("2.5");
		assertThat(statusId(CARROT_INVENTORY_ID)).isEqualTo(1);
		assertThat(eventCount(CARROT_INVENTORY_ID)).isEqualTo(1);
		assertThat(jdbc.queryForObject(
			"select event_type from consumption_events where inventory_item_id = 1", String.class))
			.isEqualTo("consumed");
	}

	@Test
	void usedQuantityEqualToRemainingConsumesInventoryAndRecordsEvent() throws Exception {
		complete("""
			{"usages":[{"inventoryId":1,"usedQuantity":4}]}
			""").andExpect(status().isOk());

		assertThat(remaining(CARROT_INVENTORY_ID)).isEqualByComparingTo("0");
		assertThat(statusId(CARROT_INVENTORY_ID)).isEqualTo(2);
		assertThat(eventCount(CARROT_INVENTORY_ID)).isEqualTo(1);
		assertThat(jdbc.queryForObject(
			"select quantity from consumption_events where inventory_item_id = 1", BigDecimal.class))
			.isEqualByComparingTo("4");
	}

	@Test
	void zeroUsedQuantityChangesNothing() throws Exception {
		complete("""
			{"usages":[{"inventoryId":1,"usedQuantity":0}]}
			""").andExpect(status().isOk());

		assertThat(remaining(CARROT_INVENTORY_ID)).isEqualByComparingTo("4");
		assertThat(statusId(CARROT_INVENTORY_ID)).isEqualTo(1);
		assertThat(eventCount(CARROT_INVENTORY_ID)).isZero();
	}

	@Test
	void existingUsageRateStillWorks() throws Exception {
		complete("""
			{"usages":[{"inventoryId":1,"usageRate":50}]}
			""").andExpect(status().isOk());

		assertThat(remaining(CARROT_INVENTORY_ID)).isEqualByComparingTo("2");
	}

	@Test
	void usageRateOfHundredConsumesInventory() throws Exception {
		complete("""
			{"usages":[{"inventoryId":1,"usageRate":100}]}
			""").andExpect(status().isOk());

		assertThat(remaining(CARROT_INVENTORY_ID)).isEqualByComparingTo("0");
		assertThat(statusId(CARROT_INVENTORY_ID)).isEqualTo(2);
	}

	// ===== 요리 완료: wasteQuantity =====

	@Test
	void wasteQuantityWithPartialUsageKeepsActiveAndRecordsBothEvents() throws Exception {
		complete("""
			{"usages":[{"inventoryId":1,"usedQuantity":1,"wasteQuantity":1}]}
			""").andExpect(status().isOk());

		assertThat(remaining(CARROT_INVENTORY_ID)).isEqualByComparingTo("2");
		assertThat(statusId(CARROT_INVENTORY_ID)).isEqualTo(1);
		assertThat(eventCount(CARROT_INVENTORY_ID)).isEqualTo(2);
		assertThat(jdbc.queryForObject(
			"select quantity from consumption_events where inventory_item_id = 1 and event_type = 'consumed'",
			BigDecimal.class)).isEqualByComparingTo("1");
		assertThat(jdbc.queryForObject(
			"select quantity from consumption_events where inventory_item_id = 1 and event_type = 'disposed'",
			BigDecimal.class)).isEqualByComparingTo("1");
	}

	@Test
	void wasteQuantityConsumingEverythingEndsAsConsumedStatus() throws Exception {
		complete("""
			{"usages":[{"inventoryId":1,"usedQuantity":3,"wasteQuantity":1}]}
			""").andExpect(status().isOk());

		assertThat(remaining(CARROT_INVENTORY_ID)).isEqualByComparingTo("0");
		assertThat(statusId(CARROT_INVENTORY_ID)).isEqualTo(2);
		assertThat(eventCount(CARROT_INVENTORY_ID)).isEqualTo(2);
	}

	@Test
	void wasteQuantityAloneWithoutUsedQuantityIsRecordedAsDisposedOnly() throws Exception {
		complete("""
			{"usages":[{"inventoryId":1,"usedQuantity":0,"wasteQuantity":1}]}
			""").andExpect(status().isOk());

		assertThat(remaining(CARROT_INVENTORY_ID)).isEqualByComparingTo("3");
		assertThat(statusId(CARROT_INVENTORY_ID)).isEqualTo(1);
		assertThat(eventCount(CARROT_INVENTORY_ID)).isEqualTo(1);
		assertThat(jdbc.queryForObject(
			"select event_type from consumption_events where inventory_item_id = 1", String.class))
			.isEqualTo("disposed");
	}

	@Test
	void usedQuantityPlusWasteQuantityOverRemainingIsRejected() throws Exception {
		complete("""
			{"usages":[{"inventoryId":1,"usedQuantity":3,"wasteQuantity":2}]}
			""")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

		assertThat(remaining(CARROT_INVENTORY_ID)).isEqualByComparingTo("4");
		assertThat(eventCount(CARROT_INVENTORY_ID)).isZero();
	}

	@Test
	void negativeWasteQuantityIsRejected() throws Exception {
		complete("""
			{"usages":[{"inventoryId":1,"usedQuantity":1,"wasteQuantity":-1}]}
			""")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

		assertThat(remaining(CARROT_INVENTORY_ID)).isEqualByComparingTo("4");
	}

	// ===== 요리 완료: 검증 실패 (400) =====

	@Test
	void usedQuantityOverRemainingIsRejectedWithoutChangingAnyInventory() throws Exception {
		// 첫 항목은 정상이지만 두 번째 항목이 남은 수량(500)을 초과하므로 전체가 반영되지 않아야 한다
		complete("""
			{"usages":[{"inventoryId":1,"usedQuantity":1},{"inventoryId":2,"usedQuantity":500.001}]}
			""")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

		assertThat(remaining(CARROT_INVENTORY_ID)).isEqualByComparingTo("4");
		assertThat(remaining(POTATO_INVENTORY_ID)).isEqualByComparingTo("500");
	}

	@Test
	void negativeUsedQuantityIsRejected() throws Exception {
		complete("""
			{"usages":[{"inventoryId":1,"usedQuantity":-1}]}
			""")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

		assertThat(remaining(CARROT_INVENTORY_ID)).isEqualByComparingTo("4");
	}

	@Test
	void usedQuantityWithMoreThanThreeDecimalPlacesIsRejected() throws Exception {
		complete("""
			{"usages":[{"inventoryId":1,"usedQuantity":1.2345}]}
			""")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

		assertThat(remaining(CARROT_INVENTORY_ID)).isEqualByComparingTo("4");
	}

	@Test
	void usageRateAndUsedQuantityTogetherAreRejected() throws Exception {
		complete("""
			{"usages":[{"inventoryId":1,"usageRate":50,"usedQuantity":1}]}
			""")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

		assertThat(remaining(CARROT_INVENTORY_ID)).isEqualByComparingTo("4");
	}

	@Test
	void missingBothUsageRateAndUsedQuantityIsRejected() throws Exception {
		complete("""
			{"usages":[{"inventoryId":1}]}
			""")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

		assertThat(remaining(CARROT_INVENTORY_ID)).isEqualByComparingTo("4");
	}

	@Test
	void duplicateInventoryIsRejected() throws Exception {
		complete("""
			{"usages":[{"inventoryId":1,"usedQuantity":1},{"inventoryId":1,"usedQuantity":1}]}
			""")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("DUPLICATE_INVENTORY"));

		assertThat(remaining(CARROT_INVENTORY_ID)).isEqualByComparingTo("4");
	}

	// ===== 요리 완료: 재고 접근 오류 =====

	@Test
	void otherUsersInventoryIsForbiddenWithoutChangingOwnInventory() throws Exception {
		complete("""
			{"usages":[{"inventoryId":1,"usedQuantity":1},{"inventoryId":3,"usedQuantity":1}]}
			""")
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("INVENTORY_ACCESS_DENIED"));

		assertThat(remaining(CARROT_INVENTORY_ID)).isEqualByComparingTo("4");
		assertThat(remaining(OTHER_USER_INVENTORY_ID)).isEqualByComparingTo("4");
	}

	@Test
	void missingInventoryIsNotFoundEvenWhenAnotherIdBelongsToOtherUser() throws Exception {
		complete("""
			{"usages":[{"inventoryId":3,"usedQuantity":1},{"inventoryId":999,"usedQuantity":1}]}
			""")
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("INVENTORY_NOT_FOUND"));
	}

	@Test
	void alreadyConsumedInventoryIsConflict() throws Exception {
		complete("""
			{"usages":[{"inventoryId":1,"usedQuantity":1},{"inventoryId":4,"usedQuantity":1}]}
			""")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("INVENTORY_NOT_ACTIVE"));

		assertThat(remaining(CARROT_INVENTORY_ID)).isEqualByComparingTo("4");
	}

	// ===== helpers =====

	private ResultActions complete(String body) throws Exception {
		return mvc.perform(post("/api/me/recipes/1/complete")
			.header("Authorization", bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private void insertInventory(long id, long userId, long catalogId, int unitId, int statusId,
		String initialQuantity, String remainingQuantity) {
		jdbc.update("""
			insert into inventory_items (id,user_id,ingredient_catalog_id,unit_id,status_id,
			initial_quantity,remaining_quantity,created_at,updated_at)
			values (?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
			""", id, userId, catalogId, unitId, statusId, new BigDecimal(initialQuantity),
			new BigDecimal(remainingQuantity));
	}

	private BigDecimal remaining(long inventoryId) {
		entityManager.flush();
		return jdbc.queryForObject("select remaining_quantity from inventory_items where id = ?",
			BigDecimal.class, inventoryId);
	}

	private Integer statusId(long inventoryId) {
		entityManager.flush();
		return jdbc.queryForObject("select status_id from inventory_items where id = ?",
			Integer.class, inventoryId);
	}

	private Integer eventCount(long inventoryId) {
		entityManager.flush();
		return jdbc.queryForObject("select count(*) from consumption_events where inventory_item_id = ?",
			Integer.class, inventoryId);
	}

	private String bearer() {
		return "Bearer " + tokens.issue(42L).accessToken();
	}
}
