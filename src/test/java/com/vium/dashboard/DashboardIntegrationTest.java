package com.vium.dashboard;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.auth.service.TokenService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:dashboard-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Sql(scripts = "/db/auth-users.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class DashboardIntegrationTest {

	// Asia/Seoul 기준 2026-11-16 12:00
	private static final Instant NOW = Instant.parse("2026-11-16T03:00:00Z");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TokenService tokenService;

	@MockitoBean
	private Clock clock;

	@BeforeEach
	void setUp() {
		when(clock.instant()).thenReturn(NOW);
		when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));

		jdbc.update("delete from \"consumption_events\"");
		jdbc.update("delete from \"inventory_items\"");
		jdbc.update("delete from \"user_sessions\"");
		jdbc.update("delete from \"users\"");
		jdbc.update("delete from \"item_statuses\"");
		jdbc.update("""
			insert into "item_statuses" ("id", "code", "name")
			values (1, 'active', '보유중'), (2, 'consumed', '소진'), (3, 'disposed', '폐기'), (4, 'shared', '공유중')
			""");
	}

	private void insertUser(long id, String createdAtUtc) {
		jdbc.update("""
			insert into "users" ("id", "email", "display_name", "password_hash", "created_at", "updated_at")
			values (?, ?, '테스트 사용자', null, ?, ?)
			""", id, "dashboard" + id + "@example.com",
			Timestamp.valueOf(createdAtUtc), Timestamp.valueOf(createdAtUtc));
	}

	private void item(long id, long userId, String createdAt) {
		jdbc.update("""
			insert into "inventory_items"
			("id", "user_id", "custom_name", "status_id", "unit_id", "initial_quantity", "remaining_quantity", "created_at", "updated_at")
			values (?, ?, '대시보드 테스트', 1, 1, 1, 1, ?, ?)
			""", id, userId, Timestamp.valueOf(createdAt), Timestamp.valueOf(createdAt));
	}

	private void event(long itemId, long userId, String type, Integer amount, String occurredAt) {
		jdbc.update("""
			insert into "consumption_events"
			("inventory_item_id", "user_id", "event_type", "event_source_id", "quantity", "amount", "occurred_at", "created_at")
			values (?, ?, ?, 1, 1, ?, ?, CURRENT_TIMESTAMP)
			""", itemId, userId, type, amount, Timestamp.valueOf(occurredAt));
	}

	private String auth(long userId) {
		return "Bearer " + tokenService.issue(userId).accessToken();
	}

	@Test
	void newUserWithNoEventsCountsStreakFromJoinDate() throws Exception {
		insertUser(42, "2026-11-10 00:00:00");

		mockMvc.perform(get("/api/me/dashboard").header("Authorization", auth(42)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.savingStreakDays").value(7))
			.andExpect(jsonPath("$.data.lastDisposedOn").value(nullValue()))
			.andExpect(jsonPath("$.data.totalSavedAmount").value(0))
			.andExpect(jsonPath("$.data.thisMonth.yearMonth").value("2026-11"))
			.andExpect(jsonPath("$.data.thisMonth.savedAmount").value(0))
			.andExpect(jsonPath("$.data.thisMonth.wastedAmount").value(0))
			.andExpect(jsonPath("$.data.activeIngredientCount").value(0))
			.andExpect(jsonPath("$.data.expiringSoonCount").value(0));
	}

	@Test
	void disposalTodayGivesZeroStreak() throws Exception {
		insertUser(42, "2026-01-01 00:00:00");
		item(1, 42, "2026-11-01 00:00:00");
		event(1, 42, "disposed", 500, "2026-11-16 09:00:00");

		mockMvc.perform(get("/api/me/dashboard").header("Authorization", auth(42)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.savingStreakDays").value(0))
			.andExpect(jsonPath("$.data.lastDisposedOn").value("2026-11-16"))
			.andExpect(jsonPath("$.data.thisMonth.wastedAmount").value(500))
			.andExpect(jsonPath("$.data.thisMonth.wastedCount").value(1));
	}

	@Test
	void sharedEventCountsTowardSavings() throws Exception {
		insertUser(42, "2026-01-01 00:00:00");
		item(2, 42, "2026-11-01 00:00:00");
		event(2, 42, "shared", 700, "2026-11-05 10:00:00");

		mockMvc.perform(get("/api/me/dashboard").header("Authorization", auth(42)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totalSavedAmount").value(700))
			.andExpect(jsonPath("$.data.thisMonth.savedAmount").value(700))
			.andExpect(jsonPath("$.data.thisMonth.sharedCount").value(1));
	}

	@Test
	void differentUsersDataIsIsolated() throws Exception {
		insertUser(42, "2026-01-01 00:00:00");
		insertUser(43, "2026-01-01 00:00:00");
		item(3, 43, "2026-11-01 00:00:00");
		event(3, 43, "disposed", 9999, "2026-11-16 09:00:00");

		mockMvc.perform(get("/api/me/dashboard").header("Authorization", auth(42)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.lastDisposedOn").value(nullValue()))
			.andExpect(jsonPath("$.data.thisMonth.wastedAmount").value(0));
	}
}
