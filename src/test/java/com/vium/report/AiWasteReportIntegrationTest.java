package com.vium.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.auth.service.TokenService;
import com.vium.report.client.LlmReportClient;
import com.vium.report.dto.CreateAiWasteReportRequest;
import com.vium.report.dto.InsightDto;
import com.vium.report.dto.LlmReportResponse;
import com.vium.report.service.AiWasteReportService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:ai-waste-report-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Sql(scripts = "/db/inventory-query-support.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class AiWasteReportIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-11-16T03:00:00Z");
	private static final Long USER_A = 42L;
	private static final Long USER_B = 43L;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TokenService tokenService;

	@Autowired
	private AiWasteReportService aiWasteReportService;

	@Autowired
	private JsonMapper jsonMapper;

	@MockitoBean
	private LlmReportClient llmReportClient;

	@MockitoBean
	private Clock clock;

	@BeforeEach
	void setUp() {
		when(clock.instant()).thenReturn(NOW);
		when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));

		jdbcTemplate.update("delete from \"credit_transactions\"");
		jdbcTemplate.update("delete from \"ai_waste_reports\"");
		jdbcTemplate.update("delete from \"consumption_events\"");
		jdbcTemplate.update("delete from \"inventory_items\"");
		jdbcTemplate.update("delete from \"ingredient_catalog\"");
		jdbcTemplate.update("delete from \"ingredient_categories\"");
		jdbcTemplate.update("delete from \"users\"");

		jdbcTemplate.update("""
			insert into "users" ("id", "email", "display_name", "created_at", "updated_at")
			values (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""", USER_A, "user-a@example.com", "UserA");
		jdbcTemplate.update("""
			insert into "users" ("id", "email", "display_name", "created_at", "updated_at")
			values (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""", USER_B, "user-b@example.com", "UserB");

		jdbcTemplate.update("insert into \"ingredient_categories\" (\"id\", \"name\") values (1, '채소')");
		jdbcTemplate.update("""
			insert into "ingredient_catalog" ("id", "name", "category_id", "default_unit_id", "created_at")
			values (1, '상추', 1, 1, CURRENT_TIMESTAMP)
			""");

		// 2026-10월 폐기 이벤트(USER_A). NOT_ENOUGH_DATA를 트리거하려는 테스트에서는 이 달을 쓰지 않는다.
		jdbcTemplate.update("""
			insert into "inventory_items"
			("id", "user_id", "ingredient_catalog_id", "status_id", "unit_id",
			 "initial_quantity", "remaining_quantity", "amount", "purchased_on", "created_at", "updated_at")
			values (100, ?, 1, 3, 1, 1, 0, 2000, DATE '2026-10-01', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""", USER_A);
		jdbcTemplate.update("""
			insert into "consumption_events"
			("inventory_item_id", "user_id", "event_type", "event_source_id", "quantity", "amount", "occurred_at", "created_at")
			values (100, ?, 'disposed', 1, 1, 2000, TIMESTAMP '2026-10-15 10:00:00', CURRENT_TIMESTAMP)
			""", USER_A);
	}

	private void seedBalance(Long userId, int amount) {
		jdbcTemplate.update("""
			insert into "credit_transactions" ("user_id", "type", "amount", "created_at")
			values (?, 'TRADE_REFUND', ?, CURRENT_TIMESTAMP)
			""", userId, amount);
	}

	private LlmReportResponse validLlmResponse() {
		return new LlmReportResponse("10월에는 잎채소 폐기가 많았어요.",
			List.of(new InsightDto("잎채소를 자주 버려요", "상추를 자주 폐기했어요.")),
			List.of("다음에는 상추를 절반만 사보세요."));
	}

	private String bearer(Long userId) {
		return "Bearer " + tokenService.issue(userId).accessToken();
	}

	@Test
	void createsReportAndDeductsCreditInOneTransaction() throws Exception {
		seedBalance(USER_A, 100);
		when(llmReportClient.generateReport(any())).thenReturn(validLlmResponse());

		mockMvc.perform(post("/api/me/ai-waste-reports").header("Authorization", bearer(USER_A))
				.contentType(MediaType.APPLICATION_JSON).content("{\"yearMonth\":\"2026-10\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.yearMonth").value("2026-10"))
			.andExpect(jsonPath("$.data.cost").value(5))
			.andExpect(jsonPath("$.data.chargedCredit").value(5))
			.andExpect(jsonPath("$.data.summary").value("10월에는 잎채소 폐기가 많았어요."))
			.andExpect(jsonPath("$.data.insights[0].title").value("잎채소를 자주 버려요"))
			.andExpect(jsonPath("$.data.suggestions[0]").value("다음에는 상추를 절반만 사보세요."));

		assertThat(jdbcTemplate.queryForObject(
			"select count(*) from ai_waste_reports where user_id = ? and year_month = '2026-10'",
			Integer.class, USER_A)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject(
			"select amount from credit_transactions where user_id = ? and type = 'AI_REPORT'",
			Integer.class, USER_A)).isEqualTo(-5);
		assertThat(jdbcTemplate.queryForObject(
			"select ai_waste_report_id from credit_transactions where user_id = ? and type = 'AI_REPORT'",
			Long.class, USER_A)).isNotNull();
	}

	@Test
	void reRequestingSameMonthReturnsExistingReportWithoutCharging() throws Exception {
		seedBalance(USER_A, 100);
		when(llmReportClient.generateReport(any())).thenReturn(validLlmResponse());

		String firstResponse = mockMvc.perform(post("/api/me/ai-waste-reports").header("Authorization", bearer(USER_A))
				.contentType(MediaType.APPLICATION_JSON).content("{\"yearMonth\":\"2026-10\"}"))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		Long firstId = extractAiReportId(firstResponse);

		mockMvc.perform(post("/api/me/ai-waste-reports").header("Authorization", bearer(USER_A))
				.contentType(MediaType.APPLICATION_JSON).content("{\"yearMonth\":\"2026-10\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.aiReportId").value(firstId))
			.andExpect(jsonPath("$.data.chargedCredit").value(0));

		verify(llmReportClient, times(1)).generateReport(any());
		assertThat(jdbcTemplate.queryForObject(
			"select count(*) from credit_transactions where user_id = ? and type = 'AI_REPORT'",
			Integer.class, USER_A)).isEqualTo(1);
	}

	@Test
	void insufficientBalanceReturns409WithoutCallingLlm() throws Exception {
		mockMvc.perform(post("/api/me/ai-waste-reports").header("Authorization", bearer(USER_A))
				.contentType(MediaType.APPLICATION_JSON).content("{\"yearMonth\":\"2026-10\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("INSUFFICIENT_CREDIT"));

		verify(llmReportClient, never()).generateReport(any());
		assertThat(jdbcTemplate.queryForObject("select count(*) from ai_waste_reports", Integer.class)).isZero();
	}

	@Test
	void monthWithNoEventsReturns422() throws Exception {
		seedBalance(USER_A, 100);

		mockMvc.perform(post("/api/me/ai-waste-reports").header("Authorization", bearer(USER_A))
				.contentType(MediaType.APPLICATION_JSON).content("{\"yearMonth\":\"2026-09\"}"))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.error.code").value("NOT_ENOUGH_DATA"));

		verify(llmReportClient, never()).generateReport(any());
	}

	@Test
	void futureMonthReturns400() throws Exception {
		seedBalance(USER_A, 100);

		mockMvc.perform(post("/api/me/ai-waste-reports").header("Authorization", bearer(USER_A))
				.contentType(MediaType.APPLICATION_JSON).content("{\"yearMonth\":\"2026-12\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

		verify(llmReportClient, never()).generateReport(any());
	}

	@Test
	void llmFailureAfterOneRetryReturns503WithoutChargeOrSave() throws Exception {
		seedBalance(USER_A, 100);
		when(llmReportClient.generateReport(any())).thenThrow(new RuntimeException("boom"));

		mockMvc.perform(post("/api/me/ai-waste-reports").header("Authorization", bearer(USER_A))
				.contentType(MediaType.APPLICATION_JSON).content("{\"yearMonth\":\"2026-10\"}"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.error.code").value("AI_REPORT_GENERATION_FAILED"));

		verify(llmReportClient, times(2)).generateReport(any());
		assertThat(jdbcTemplate.queryForObject("select count(*) from ai_waste_reports", Integer.class)).isZero();
		assertThat(jdbcTemplate.queryForObject(
			"select count(*) from credit_transactions where type = 'AI_REPORT'", Integer.class)).isZero();
		assertThat(jdbcTemplate.queryForObject(
			"select coalesce(sum(amount), 0) from credit_transactions where user_id = ?", Integer.class, USER_A))
			.isEqualTo(100);
	}

	@Test
	void concurrentDuplicateRequestsChargeOnlyOnce() throws Exception {
		seedBalance(USER_A, 100);
		when(llmReportClient.generateReport(any())).thenReturn(validLlmResponse());

		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Callable<Integer> call = () -> {
				ready.countDown();
				start.await();
				return aiWasteReportService.create(USER_A, new CreateAiWasteReportRequest("2026-10")).chargedCredit();
			};
			Future<Integer> first = executor.submit(call);
			Future<Integer> second = executor.submit(call);

			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();

			int firstCharge = first.get(10, TimeUnit.SECONDS);
			int secondCharge = second.get(10, TimeUnit.SECONDS);
			assertThat(firstCharge + secondCharge).isEqualTo(5);
		} finally {
			executor.shutdown();
			executor.awaitTermination(10, TimeUnit.SECONDS);
		}

		assertThat(jdbcTemplate.queryForObject(
			"select count(*) from ai_waste_reports where user_id = ? and year_month = '2026-10'",
			Integer.class, USER_A)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject(
			"select count(*) from credit_transactions where user_id = ? and type = 'AI_REPORT'",
			Integer.class, USER_A)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject(
			"select coalesce(sum(amount), 0) from credit_transactions where user_id = ?", Integer.class, USER_A))
			.isEqualTo(95);
	}

	@Test
	void listsReportsForOwnerAndReturns404ForOtherUser() throws Exception {
		seedBalance(USER_A, 100);
		when(llmReportClient.generateReport(any())).thenReturn(validLlmResponse());

		String createResponse = mockMvc.perform(post("/api/me/ai-waste-reports").header("Authorization", bearer(USER_A))
				.contentType(MediaType.APPLICATION_JSON).content("{\"yearMonth\":\"2026-10\"}"))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		Long reportId = extractAiReportId(createResponse);

		mockMvc.perform(get("/api/me/ai-waste-reports").header("Authorization", bearer(USER_A)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.reports.length()").value(1))
			.andExpect(jsonPath("$.data.reports[0].yearMonth").value("2026-10"));

		mockMvc.perform(get("/api/me/ai-waste-reports/{id}", reportId).header("Authorization", bearer(USER_A)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.summary").value("10월에는 잎채소 폐기가 많았어요."));

		mockMvc.perform(get("/api/me/ai-waste-reports/{id}", reportId).header("Authorization", bearer(USER_B)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	private Long extractAiReportId(String responseBody) {
		return jsonMapper.readTree(responseBody).path("data").path("aiReportId").asLong();
	}
}
