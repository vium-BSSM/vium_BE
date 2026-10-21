package com.vium.dispose;

import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.auth.service.TokenService;
import com.vium.dispose.dto.WasteReportDto;
import com.vium.dispose.repository.WasteReportQueryRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

// findReports()는 PostgreSQL 전용 SQL이라 H2에서 못 돌리므로 스텁하고, 새로 추가된 집계 쿼리만 H2로 검증한다.
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:waste-report-summary-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class WasteReportSummaryIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TokenService tokenService;

	@MockitoSpyBean
	private WasteReportQueryRepository repository;

	@BeforeEach
	void setUp() {
		jdbc.update("delete from \"consumption_events\"");
		jdbc.update("delete from \"inventory_items\"");
		jdbc.update("delete from \"ingredient_catalog\"");
		jdbc.update("insert into \"ingredient_catalog\" (\"id\", \"name\", \"default_unit_id\", \"created_at\") values (1, '우유', 1, CURRENT_TIMESTAMP)");
	}

	private void item(long id, long userId, String customName, Long catalogId, Integer amount) {
		jdbc.update("""
			insert into "inventory_items"
			("id", "user_id", "ingredient_catalog_id", "custom_name", "status_id", "unit_id", "initial_quantity", "remaining_quantity", "amount", "created_at", "updated_at")
			values (?, ?, ?, ?, 1, 1, 1, 1, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""", id, userId, catalogId, customName, amount);
	}

	private void event(long itemId, long userId, String type, Integer amount, String at) {
		jdbc.update("""
			insert into "consumption_events"
			("inventory_item_id", "user_id", "event_type", "event_source_id", "quantity", "amount", "occurred_at", "created_at")
			values (?, ?, ?, 1, 1, ?, ?, CURRENT_TIMESTAMP)
			""", itemId, userId, type, amount, java.sql.Timestamp.valueOf(at));
	}

	private void stubReports(WasteReportDto... reports) {
		doReturn(List.of(reports)).when(repository).findReports(42L);
	}

	private WasteReportDto october() {
		return new WasteReportDto(202610L, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), 0L, 0L);
	}

	private String auth() {
		return "Bearer " + tokenService.issue(42L).accessToken();
	}

	@Test
	void countsAndTopItemsForMonth() throws Exception {
		item(1, 42, "두부", null, 2000);
		item(2, 42, null, 1L, 3000);      // 이름은 카탈로그에서
		item(3, 42, "사과", null, 1000);
		item(4, 42, "빵", null, null);    // 구매금액 없음
		item(5, 42, "계란", null, 4000);
		item(6, 42, "우동", null, 1000);
		// 두부: 일부 폐기 + 일부 섭취 -> 폐기로 분류, 폐기금액 합산
		event(1, 42, "disposed", 500, "2026-10-03 10:00:00");
		event(1, 42, "disposed", 700, "2026-10-20 10:00:00");
		event(1, 42, "consumed", null, "2026-10-05 10:00:00");
		event(2, 42, "disposed", 3000, "2026-10-31 23:59:59");
		event(3, 42, "disposed", 200, "2026-10-10 00:00:00");
		event(4, 42, "disposed", 900, "2026-10-11 00:00:00");
		event(5, 42, "consumed", null, "2026-10-12 00:00:00");
		event(5, 42, "consumed", null, "2026-10-13 00:00:00");
		// 다른 달/다른 사용자는 제외
		event(6, 42, "disposed", 9999, "2026-11-01 00:00:00");
		event(6, 42, "disposed", 9999, "2026-09-30 23:59:59");
		item(7, 99, "남의것", null, 100);
		event(7, 99, "disposed", 9999, "2026-10-05 00:00:00");
		stubReports(october());

		mockMvc.perform(get("/api/me/waste-reports").header("Authorization", auth()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.reports[0].wasteReportId").value(202610))
			.andExpect(jsonPath("$.data.reports[0].registeredCount").value(5))
			.andExpect(jsonPath("$.data.reports[0].consumedCount").value(1))
			.andExpect(jsonPath("$.data.reports[0].expiredCount").value(4))
			.andExpect(jsonPath("$.data.reports[0].topWastedItems.length()").value(3))
			.andExpect(jsonPath("$.data.reports[0].topWastedItems[0].rank").value(1))
			.andExpect(jsonPath("$.data.reports[0].topWastedItems[0].name").value("우유"))
			.andExpect(jsonPath("$.data.reports[0].topWastedItems[0].lossAmount").value(3000))
			.andExpect(jsonPath("$.data.reports[0].topWastedItems[0].purchaseAmount").value(3000))
			.andExpect(jsonPath("$.data.reports[0].topWastedItems[0].wasteRatio").value(1.0))
			.andExpect(jsonPath("$.data.reports[0].topWastedItems[1].name").value("두부"))
			.andExpect(jsonPath("$.data.reports[0].topWastedItems[1].lossAmount").value(1200))
			.andExpect(jsonPath("$.data.reports[0].topWastedItems[1].wasteRatio").value(0.6))
			.andExpect(jsonPath("$.data.reports[0].topWastedItems[2].name").value("빵"))
			.andExpect(jsonPath("$.data.reports[0].topWastedItems[2].purchaseAmount").doesNotExist())
			.andExpect(jsonPath("$.data.reports[0].topWastedItems[2].wasteRatio").doesNotExist());
	}

	@Test
	void monthWithoutEventsHasZeroCountsAndEmptyTop() throws Exception {
		stubReports(october());

		mockMvc.perform(get("/api/me/waste-reports").header("Authorization", auth()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.reports[0].registeredCount").value(0))
			.andExpect(jsonPath("$.data.reports[0].consumedCount").value(0))
			.andExpect(jsonPath("$.data.reports[0].expiredCount").value(0))
			.andExpect(jsonPath("$.data.reports[0].topWastedItems.length()").value(0));
	}

	@Test
	void existingFieldsAreUnchanged() throws Exception {
		stubReports(new WasteReportDto(202610L, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), 1500L, 0L));

		mockMvc.perform(get("/api/me/waste-reports").header("Authorization", auth()))
			.andExpect(jsonPath("$.data.reports[0].periodStart").value("2026-10-01"))
			.andExpect(jsonPath("$.data.reports[0].periodEnd").value("2026-10-31"))
			.andExpect(jsonPath("$.data.reports[0].totalWastedAmount").value(1500))
			.andExpect(jsonPath("$.data.reports[0].totalSavedAmount").value(0));
	}
}
