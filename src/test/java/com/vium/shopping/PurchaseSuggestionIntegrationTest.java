package com.vium.shopping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.vium.auth.service.TokenService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:purchase-suggestion-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Transactional
class PurchaseSuggestionIntegrationTest {

	private static final String URL = "/api/me/purchase-suggestions";
	private static final String HELPER_URL = "/api/me/shopping-helper";
	private static final long USER = 42L;
	private static final long OTHER_USER = 43L;
	private static final LocalDate MONTH_START = LocalDate.of(2026, 10, 1);

	@Autowired private MockMvc mvc;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private TokenService tokenService;
	@MockitoBean private Clock clock;

	@BeforeEach
	void setUp() {
		when(clock.instant()).thenReturn(Instant.parse("2026-10-31T15:30:00Z"));
		when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
		jdbc.update("insert into item_statuses (id, code, name) values (1, 'active', '보유'), (2, 'consumed', '소진')");
		jdbc.update("insert into units (id, code, name) values (1, 'ea', '개'), (2, 'g', 'g')");
		jdbc.update("insert into event_sources (id, code, name) values (1, 'manual', '수동')");
	}

	@Test
	void aggregatesLastCompletedSeoulMonthAndCurrentStockForTheSameItem() throws Exception {
		stock(100, USER, "우유", 1, 0, null);
		stock(101, USER, "우유", 1, 1, MONTH_START.minusMonths(2));
		stock(102, USER, "우유", 1, 2, MONTH_START.plusMonths(1));
		event(100, USER, "consumed", "5", MONTH_START);
		event(100, USER, "consumed", "3", MONTH_START.plusDays(30));
		event(100, USER, "disposed", "2", MONTH_START.plusDays(30));
		event(100, USER, "consumed", "100", MONTH_START.minusDays(1));
		event(100, USER, "consumed", "100", MONTH_START.plusMonths(1));
		event(100, USER, "shared", "100", MONTH_START);

		suggestions().andExpect(jsonPath("$.data.available").value(true))
			.andExpect(jsonPath("$.data.basedOnPeriodStart").value("2026-10"))
			.andExpect(jsonPath("$.data.basedOnPeriodEnd").value("2026-10"))
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].ingredientCatalogId").value(nullValue()))
			.andExpect(jsonPath("$.data.items[0].ingredientName").value("우유"))
			.andExpect(jsonPath("$.data.items[0].unitId").value(1))
			.andExpect(jsonPath("$.data.items[0].unit").value("개"))
			.andExpect(jsonPath("$.data.items[0].consumedQuantity").value(8))
			.andExpect(jsonPath("$.data.items[0].disposedQuantity").value(2))
			.andExpect(jsonPath("$.data.items[0].currentQuantity").value(3))
			.andExpect(jsonPath("$.data.items[0].wasteRatio").value(0.2))
			.andExpect(jsonPath("$.data.items[0].suggestedQuantity").value(4));
	}

	@Test
	void fallsBackToThreeCompletedMonthsOnlyWhenTheHelperHasNoLastMonthCandidates() throws Exception {
		stock(100, USER, "우유", 1, 0, null);
		event(100, USER, "consumed", "5", MONTH_START.minusMonths(2));
		event(100, USER, "disposed", "2", MONTH_START.minusMonths(1));
		event(100, USER, "consumed", "100", MONTH_START.minusMonths(2).minusDays(1));

		suggestions().andExpect(jsonPath("$.data.basedOnPeriodStart").value("2026-08"))
			.andExpect(jsonPath("$.data.basedOnPeriodEnd").value("2026-10"))
			.andExpect(jsonPath("$.data.items[0].consumedQuantity").value(5))
			.andExpect(jsonPath("$.data.items[0].disposedQuantity").value(2));
	}

	@Test
	void doesNotWidenWhenLastMonthHasOnlyAnEventlessPurchaseAndOlderEventsExist() throws Exception {
		stock(100, USER, "어제 산 재료", 1, 2, MONTH_START.plusDays(10));
		stock(101, USER, "이전 달 재료", 1, 0, null);
		event(101, USER, "consumed", "5", MONTH_START.minusMonths(1));

		mvc.perform(get(HELPER_URL).header("Authorization", bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.basedOnPeriodStart").value("2026-10"))
			.andExpect(jsonPath("$.data.alreadyHave[0].ingredientName").value("어제 산 재료"));
		expectUnavailable();
	}

	@Test
	void excludesEventlessPurchasesAndOtherUsersEventsAndCurrentStock() throws Exception {
		stock(100, USER, "우유", 1, 0, null);
		event(100, USER, "consumed", "5", MONTH_START);
		stock(101, USER, "구매 재고", 1, 2, MONTH_START);
		stock(102, OTHER_USER, "다른 재료", 1, 0, null);
		event(102, OTHER_USER, "disposed", "100", MONTH_START);
		stock(103, OTHER_USER, "우유", 1, 14, MONTH_START);
		event(103, OTHER_USER, "consumed", "100", MONTH_START);

		suggestions().andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].ingredientName").value("우유"))
			.andExpect(jsonPath("$.data.items[0].currentQuantity").value(0))
			.andExpect(jsonPath("$.data.items[0].suggestedQuantity").value(5));
	}

	@Test
	void usesExactAdjustmentMessagesAtEachBoundaryAndPrioritizesHavingEnough() throws Exception {
		history(100, "충분", 1, 2, "2", "8");
		history(101, "절반", 1, 0, "5", "5");
		history(102, "조금", 1, 0, "8", "2");
		history(103, "유지", 1, 0, "9", "1");

		suggestions()
			.andExpect(jsonPath("$.data.items[0].suggestedQuantity").value(0))
			.andExpect(jsonPath("$.data.items[1].wasteRatio").value(0.5))
			.andExpect(jsonPath("$.data.items[2].wasteRatio").value(0.2))
			.andExpect(jsonPath("$.data.items[*].adjustment", contains("HAVE_ENOUGH", "REDUCE_HALF", "REDUCE_SOME", "KEEP")))
			.andExpect(jsonPath("$.data.items[*].message", contains(
				"아직 집에 있어요. 이번엔 안 사도 괜찮아요",
				"지난번엔 절반 넘게 남았어요. 이번엔 절반만 사 보세요",
				"지난번엔 조금 남았어요. 이번엔 조금 덜 사 보세요",
				"지난번 양이 잘 맞았어요")));
	}

	@Test
	void sortsByWasteRatioThenNameThenUnitAndIncludesDisposedOnlyHistory() throws Exception {
		history(100, "나", 1, 0, "5", "5");
		history(101, "가", 2, 0, "5", "5");
		history(102, "가", 1, 0, "5", "5");
		stock(103, USER, "다", 1, 0, null);
		event(103, USER, "disposed", "9", MONTH_START);

		suggestions().andExpect(jsonPath("$.data.items[*].ingredientName", contains("다", "가", "가", "나")))
			.andExpect(jsonPath("$.data.items[*].unitId", contains(1, 1, 2, 1)))
			.andExpect(jsonPath("$.data.items[0].consumedQuantity").value(0))
			.andExpect(jsonPath("$.data.items[0].wasteRatio").value(1))
			.andExpect(jsonPath("$.data.items[0].suggestedQuantity").value(0));
	}

	@Test
	void matchesHelperSuggestedQuantityForTheSameCountAndWeightItems() throws Exception {
		history(100, "계란", 1, 0, "5", "2");
		history(101, "쌀", 2, 0, "5", "2");

		String suggestionsBody = suggestions()
			.andExpect(jsonPath("$.data.items[0].wasteRatio").value(0.29))
			.andExpect(jsonPath("$.data.items[0].suggestedQuantity").value(4))
			.andExpect(jsonPath("$.data.items[1].suggestedQuantity").value(3.571))
			.andReturn().getResponse().getContentAsString();
		String helperBody = mvc.perform(get(HELPER_URL).header("Authorization", bearer()))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

		for (String name : new String[] {"계란", "쌀"}) {
			String filter = "[?(@.ingredientName == '" + name + "')].suggestedQuantity";
			Object suggested = JsonPath.read(suggestionsBody, "$.data.items" + filter);
			Object helperSuggested = JsonPath.read(helperBody, "$.data.toBuy" + filter);
			assertThat(suggested).isEqualTo(helperSuggested);
		}
	}

	@Test
	void returnsUnavailableWhenThereAreNoCandidates() throws Exception {
		expectUnavailable();
	}

	@Test
	void requiresAuthentication() throws Exception {
		mvc.perform(get(URL)).andExpect(status().isUnauthorized());
	}

	private void expectUnavailable() throws Exception {
		suggestions().andExpect(jsonPath("$.data.available").value(false))
			.andExpect(jsonPath("$.data.basedOnPeriodStart").value(nullValue()))
			.andExpect(jsonPath("$.data.basedOnPeriodEnd").value(nullValue()))
			.andExpect(jsonPath("$.data.items").isEmpty());
	}

	private ResultActions suggestions() throws Exception {
		return mvc.perform(get(URL).header("Authorization", bearer())).andExpect(status().isOk());
	}

	private String bearer() {
		return "Bearer " + tokenService.issue(USER).accessToken();
	}

	private void history(long id, String name, int unitId, int remaining, String consumed, String disposed) {
		stock(id, USER, name, unitId, remaining, null);
		event(id, USER, "consumed", consumed, MONTH_START);
		event(id, USER, "disposed", disposed, MONTH_START);
	}

	private void stock(long id, long user, String name, int unit, int remaining, LocalDate purchasedOn) {
		jdbc.update("""
			insert into inventory_items
				(id, user_id, custom_name, status_id, unit_id, initial_quantity, remaining_quantity,
				 purchased_on, created_at, updated_at)
			values (?, ?, ?, ?, ?, 1000, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""", id, user, name, remaining > 0 ? 1 : 2, unit, remaining, purchasedOn);
	}

	private void event(long itemId, long user, String type, String quantity, LocalDate occurredOn) {
		jdbc.update("""
			insert into consumption_events
				(inventory_item_id, user_id, event_type, event_source_id, quantity, occurred_at, created_at)
			values (?, ?, ?, 1, ?, ?, CURRENT_TIMESTAMP)
			""", itemId, user, type, new BigDecimal(quantity), occurredOn.atStartOfDay());
	}
}
