package com.vium.shopping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.auth.service.TokenService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ShoppingHelperIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TokenService tokenService;

	private Long userId = 42L;

	@BeforeEach
	void setUp() {
		// ingredient_categories는 JPA 엔티티가 없어 Hibernate ddl-auto로 생성되지 않는다.
		// InventoryQueryRepository.findActiveIngredients()가 이 테이블을 조인하므로 직접 만든다.
		jdbcTemplate.update("""
			create table if not exists "ingredient_categories"
			("id" smallint primary key, "name" varchar(60) unique not null, "parent_id" smallint)
			""");

		// 테이블 정리
		jdbcTemplate.update("delete from \"consumption_events\"");
		jdbcTemplate.update("delete from \"inventory_items\"");
		jdbcTemplate.update("delete from \"item_statuses\"");
		jdbcTemplate.update("delete from \"event_sources\"");

		// 코드 테이블 삽입
		jdbcTemplate.update("insert into \"item_statuses\" (\"id\", \"code\", \"name\") values "
			+ "(1, 'active', '보유'), (2, 'consumed', '소진'), (3, 'disposed', '폐기')");
		jdbcTemplate.update("insert into \"event_sources\" (\"id\", \"code\", \"name\") values "
			+ "(1, 'manual', '수동'), (2, 'auto_estimated', '자동추정')");
		jdbcTemplate.update("delete from \"units\"");
		jdbcTemplate.update("insert into \"units\" (\"id\", \"code\", \"name\") values (1, 'ea', 'ea')");
	}

	@Test
	void shoppingHelperMapsDisposedOnlyItemNameAndUnitCorrectly() throws Exception {
		// Given: 전량 폐기되어 더 이상 active가 아닌 재료. 폐기 이벤트가 후보 판정을 트리거하고,
		// 소진 이벤트가 추천 수량(shortage)의 기준이 된다. active 재고 목록에 없어도
		// 이름/단위는 이벤트 조인 결과에서 올바르게 매핑되어야 한다.
		YearMonth lastMonth = YearMonth.now().minusMonths(1);
		LocalDate monthStart = lastMonth.atDay(1);

		Long disposedItemId = 100L;
		jdbcTemplate.update("""
			insert into "inventory_items"
			("id", "user_id", "custom_name", "status_id", "unit_id", "initial_quantity",
			 "remaining_quantity", "created_at", "updated_at")
			values (?, ?, '우유', 3, 1, 7, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""", disposedItemId, userId);

		// 지난달: 2개 폐기(후보 트리거) + 5개 소진(추천 수량 기준)
		jdbcTemplate.update("""
			insert into "consumption_events"
			("inventory_item_id", "user_id", "event_type", "event_source_id", "quantity", "occurred_at", "created_at")
			values (?, ?, 'disposed', 1, 2, ?, CURRENT_TIMESTAMP)
			""", disposedItemId, userId, monthStart.atStartOfDay());
		jdbcTemplate.update("""
			insert into "consumption_events"
			("inventory_item_id", "user_id", "event_type", "event_source_id", "quantity", "occurred_at", "created_at")
			values (?, ?, 'consumed', 1, 5, ?, CURRENT_TIMESTAMP)
			""", disposedItemId, userId, monthStart.atStartOfDay());

		mockMvc.perform(get("/api/me/shopping-helper")
				.header("Authorization", "Bearer " + tokenService.issue(userId).accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.available").value(true))
			.andExpect(jsonPath("$.data.basedOnReportMonth").value(lastMonth.toString()))
			.andExpect(jsonPath("$.data.toBuy.length()").value(1))
			.andExpect(jsonPath("$.data.toBuy[0].ingredientName").value("우유"))
			.andExpect(jsonPath("$.data.toBuy[0].unit").value("ea"))
			// shortage = 소진(5) - 현재 보유(0, active 아님) = 5
			.andExpect(jsonPath("$.data.toBuy[0].suggestedQuantity").value(5));
	}

	@Test
	void shoppingHelperExcludesConsumedOnlyItemWithoutDisposedEvent() throws Exception {
		// Given: 소진 이벤트만 있고 폐기 이벤트가 전혀 없는 재료.
		// 후보 판정은 폐기 이벤트 기준이므로(원래 동작), 이런 재료는 소진량이 커도 후보가 아니다.
		YearMonth lastMonth = YearMonth.now().minusMonths(1);
		LocalDate monthStart = lastMonth.atDay(1);

		Long consumedItemId = 101L;
		jdbcTemplate.update("""
			insert into "inventory_items"
			("id", "user_id", "custom_name", "status_id", "unit_id", "initial_quantity",
			 "remaining_quantity", "created_at", "updated_at")
			values (?, ?, '계란', 2, 1, 10, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""", consumedItemId, userId);

		jdbcTemplate.update("""
			insert into "consumption_events"
			("inventory_item_id", "user_id", "event_type", "event_source_id", "quantity", "occurred_at", "created_at")
			values (?, ?, 'consumed', 1, 10, ?, CURRENT_TIMESTAMP)
			""", consumedItemId, userId, monthStart.atStartOfDay());

		// When: 이 사용자에게는 지난달 폐기 이벤트가 전혀 없다 → unavailable
		mockMvc.perform(get("/api/me/shopping-helper")
				.header("Authorization", "Bearer " + tokenService.issue(userId).accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.available").value(false));
	}

	@Test
	void shoppingHelperHandlesPartialConsumptionCorrectly() throws Exception {
		// Given: 지난달 폐기 이벤트(후보 트리거) + 부분 소진 이벤트, 현재 일부 보유
		YearMonth lastMonth = YearMonth.now().minusMonths(1);
		LocalDate monthStart = lastMonth.atDay(1);

		Long partialItemId = 102L;
		// 현재: active, 수량 3개 (처리 후 남은 양)
		jdbcTemplate.update("""
			insert into "inventory_items"
			("id", "user_id", "custom_name", "status_id", "unit_id", "initial_quantity",
			 "remaining_quantity", "created_at", "updated_at")
			values (?, ?, '요구르트', 1, 1, 5, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""", partialItemId, userId);

		// 지난달: 1개 폐기(후보 트리거) + 2개 소진
		jdbcTemplate.update("""
			insert into "consumption_events"
			("inventory_item_id", "user_id", "event_type", "event_source_id", "quantity", "occurred_at", "created_at")
			values (?, ?, 'disposed', 1, 1, ?, CURRENT_TIMESTAMP)
			""", partialItemId, userId, monthStart.atStartOfDay());
		jdbcTemplate.update("""
			insert into "consumption_events"
			("inventory_item_id", "user_id", "event_type", "event_source_id", "quantity", "occurred_at", "created_at")
			values (?, ?, 'consumed', 1, 2, ?, CURRENT_TIMESTAMP)
			""", partialItemId, userId, monthStart.atStartOfDay());

		// When: 장보기 도우미 조회
		mockMvc.perform(get("/api/me/shopping-helper")
				.header("Authorization", "Bearer " + tokenService.issue(userId).accessToken()))
			// Then: 부족한 양 = 2(지난달 소진) - 3(현재 보유) = -1
			// → 음수이므로 toBuy에 포함 안 됨 (충분함)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.available").value(true))
			.andExpect(jsonPath("$.data.alreadyHave.length()").value(1))
			.andExpect(jsonPath("$.data.alreadyHave[0].ingredientName").value("요구르트"))
			.andExpect(jsonPath("$.data.alreadyHave[0].quantity").value(3))
			.andExpect(jsonPath("$.data.toBuy.length()").value(0));
	}

	@Test
	void shoppingHelperReturnsUnavailableWhenNoEventsInMonth() throws Exception {
		// Given: 현재 달의 이벤트만 있고, 지난달은 없음
		LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
		jdbcTemplate.update("""
			insert into "inventory_items"
			("id", "user_id", "custom_name", "status_id", "unit_id", "initial_quantity",
			 "remaining_quantity", "created_at", "updated_at")
			values (100, ?, '치즈', 1, 1, 5, 5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""", userId); // unit_id는 이미 1

		jdbcTemplate.update("""
			insert into "consumption_events"
			("inventory_item_id", "user_id", "event_type", "event_source_id", "quantity", "occurred_at", "created_at")
			values (100, ?, 'disposed', 1, 1, ?, CURRENT_TIMESTAMP)
			""", userId, today.atStartOfDay());  // 오늘 이벤트 (지난달 아님)

		// When: 장보기 도우미 조회
		mockMvc.perform(get("/api/me/shopping-helper")
				.header("Authorization", "Bearer " + tokenService.issue(userId).accessToken()))
			// Then: unavailable (지난달 이벤트 없음)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.available").value(false));
	}
}
