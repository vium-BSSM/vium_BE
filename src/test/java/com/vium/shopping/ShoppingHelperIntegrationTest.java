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
			+ "(1, 'active', '보유'), (2, 'consumed', '소진'), (3, 'disposed', '폐기'), (4, 'shared', '공유중')");
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
			// 낭비 비율 반영: 5 × 소진(5) ÷ (소진(5)+폐기(2)) = 25/7 ≈ 3.571
			// unit_id 1(개)은 낱개 단위라 올림 → 4
			.andExpect(jsonPath("$.data.toBuy[0].suggestedQuantity").value(4));
	}

	@Test
	void shoppingHelperReducesSuggestedQuantityWhenWasteRatioIsHigh() throws Exception {
		// Given: 지난달 소진(2)보다 폐기(8)가 훨씬 많은 재료 → 낭비 비율이 높을수록 추천량이 크게 줄어야 함
		YearMonth lastMonth = YearMonth.now().minusMonths(1);
		LocalDate monthStart = lastMonth.atDay(1);

		Long wastefulItemId = 103L;
		jdbcTemplate.update("""
			insert into "inventory_items"
			("id", "user_id", "custom_name", "status_id", "unit_id", "initial_quantity",
			 "remaining_quantity", "created_at", "updated_at")
			values (?, ?, '양상추', 3, 1, 10, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""", wastefulItemId, userId);

		jdbcTemplate.update("""
			insert into "consumption_events"
			("inventory_item_id", "user_id", "event_type", "event_source_id", "quantity", "occurred_at", "created_at")
			values (?, ?, 'disposed', 1, 8, ?, CURRENT_TIMESTAMP)
			""", wastefulItemId, userId, monthStart.atStartOfDay());
		jdbcTemplate.update("""
			insert into "consumption_events"
			("inventory_item_id", "user_id", "event_type", "event_source_id", "quantity", "occurred_at", "created_at")
			values (?, ?, 'consumed', 1, 2, ?, CURRENT_TIMESTAMP)
			""", wastefulItemId, userId, monthStart.atStartOfDay());

		// shortage = 소진(2) - 현재 보유(0) = 2
		// 낭비 비율 반영: 2 × 소진(2) ÷ (소진(2)+폐기(8)) = 4/10 = 0.4
		// unit_id 1(개)은 낱개 단위라 올림 → 1
		mockMvc.perform(get("/api/me/shopping-helper")
				.header("Authorization", "Bearer " + tokenService.issue(userId).accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.toBuy.length()").value(1))
			.andExpect(jsonPath("$.data.toBuy[0].ingredientName").value("양상추"))
			.andExpect(jsonPath("$.data.toBuy[0].suggestedQuantity").value(1));
	}

	@Test
	void shoppingHelperIncludesConsumedOnlyItemWithoutDisposedEvent() throws Exception {
		// Given: 소진 이벤트만 있고 폐기 이벤트가 전혀 없는 재료(예: 매달 다 먹는 계란).
		// 후보 판정은 소진 또는 폐기 이벤트 중 하나만 있어도 성립하므로 후보가 되어야 한다.
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

		// When: 폐기 이벤트가 없어도 소진 이벤트만으로 후보가 되어야 한다.
		// shortage = 소진(10) - 현재 보유(0) = 10, 폐기가 없어 낭비 비율은 1 → 10 그대로 추천
		mockMvc.perform(get("/api/me/shopping-helper")
				.header("Authorization", "Bearer " + tokenService.issue(userId).accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.available").value(true))
			.andExpect(jsonPath("$.data.basedOnReportMonth").value(lastMonth.toString()))
			.andExpect(jsonPath("$.data.toBuy.length()").value(1))
			.andExpect(jsonPath("$.data.toBuy[0].ingredientName").value("계란"))
			.andExpect(jsonPath("$.data.toBuy[0].suggestedQuantity").value(10));
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
			// Then: unavailable (최근 3개월 확장 범위에도 이벤트 없음)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.available").value(false))
			.andExpect(jsonPath("$.data.reason").value("최근 소진·폐기 기록이 없습니다"))
			.andExpect(jsonPath("$.data.basedOnPeriodStart").doesNotExist())
			.andExpect(jsonPath("$.data.basedOnPeriodEnd").doesNotExist());
	}

	@Test
	void shoppingHelperFallsBackToThreeMonthsWhenLastMonthHasNoEvents() throws Exception {
		// Given: 지난달에는 이벤트가 없고, 두 달 전(최근 3개월 범위 안)에만 소진 이벤트가 있음.
		// 가입한 지 얼마 안 됐거나 기록이 적은 달에도 추천이 나와야 한다.
		YearMonth lastMonth = YearMonth.now().minusMonths(1);
		YearMonth twoMonthsAgo = YearMonth.now().minusMonths(2);
		LocalDate twoMonthsAgoStart = twoMonthsAgo.atDay(1);

		Long itemId = 104L;
		jdbcTemplate.update("""
			insert into "inventory_items"
			("id", "user_id", "custom_name", "status_id", "unit_id", "initial_quantity",
			 "remaining_quantity", "created_at", "updated_at")
			values (?, ?, '두부', 2, 1, 4, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""", itemId, userId);

		jdbcTemplate.update("""
			insert into "consumption_events"
			("inventory_item_id", "user_id", "event_type", "event_source_id", "quantity", "occurred_at", "created_at")
			values (?, ?, 'consumed', 1, 4, ?, CURRENT_TIMESTAMP)
			""", itemId, userId, twoMonthsAgoStart.atStartOfDay());

		// 확장 범위는 항상 지난달 기준 3개월 전체(lastMonth-2 ~ lastMonth)다.
		YearMonth fallbackStart = lastMonth.minusMonths(2);
		mockMvc.perform(get("/api/me/shopping-helper")
				.header("Authorization", "Bearer " + tokenService.issue(userId).accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.available").value(true))
			// basedOnReportMonth는 확장된 경우에도 항상 "YYYY-MM" 단일 월(지난달)을 유지하고,
			// 실제 확장 범위는 basedOnPeriodStart/basedOnPeriodEnd로 표현한다.
			.andExpect(jsonPath("$.data.basedOnReportMonth").value(lastMonth.toString()))
			.andExpect(jsonPath("$.data.basedOnPeriodStart").value(fallbackStart.toString()))
			.andExpect(jsonPath("$.data.basedOnPeriodEnd").value(lastMonth.toString()))
			.andExpect(jsonPath("$.data.toBuy.length()").value(1))
			.andExpect(jsonPath("$.data.toBuy[0].ingredientName").value("두부"))
			.andExpect(jsonPath("$.data.toBuy[0].suggestedQuantity").value(4));
	}

	@Test
	void shoppingHelperBasedOnReportMonthStaysSingleMonthFormatWhenExpandedToThreeMonths() throws Exception {
		// Given: 지난달에는 이벤트가 없고, 두 달 전에만 소진 이벤트가 있어 3개월로 확장되는 상황.
		// 프론트가 basedOnReportMonth를 split('-')[1]로 파싱하므로, 확장되어도 "~" 범위 문자열이
		// 아니라 항상 "YYYY-MM" 단일 월 형식으로 와야 한다.
		YearMonth lastMonth = YearMonth.now().minusMonths(1);
		YearMonth twoMonthsAgo = YearMonth.now().minusMonths(2);
		LocalDate twoMonthsAgoStart = twoMonthsAgo.atDay(1);

		Long itemId = 105L;
		jdbcTemplate.update("""
			insert into "inventory_items"
			("id", "user_id", "custom_name", "status_id", "unit_id", "initial_quantity",
			 "remaining_quantity", "created_at", "updated_at")
			values (?, ?, '두부', 2, 1, 4, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""", itemId, userId);

		jdbcTemplate.update("""
			insert into "consumption_events"
			("inventory_item_id", "user_id", "event_type", "event_source_id", "quantity", "occurred_at", "created_at")
			values (?, ?, 'consumed', 1, 4, ?, CURRENT_TIMESTAMP)
			""", itemId, userId, twoMonthsAgoStart.atStartOfDay());

		mockMvc.perform(get("/api/me/shopping-helper")
				.header("Authorization", "Bearer " + tokenService.issue(userId).accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.available").value(true))
			.andExpect(jsonPath("$.data.basedOnReportMonth").value(org.hamcrest.Matchers.matchesPattern("\\d{4}-\\d{2}")))
			.andExpect(jsonPath("$.data.basedOnReportMonth").value(lastMonth.toString()));
	}
}
