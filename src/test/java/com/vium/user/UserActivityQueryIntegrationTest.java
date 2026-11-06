package com.vium.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.vium.user.dto.UserActivityCounts;
import com.vium.user.service.UserActivityMetrics;
import com.vium.user.service.UserActivityService;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:user-activity-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@Sql(scripts = "/db/auth-users.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class UserActivityQueryIntegrationTest {
	@Autowired private UserActivityService service;
	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private ApplicationContext context;
	@Autowired private ScheduledTaskHolder scheduledTaskHolder;
	@Autowired private MeterRegistry registry;
	@MockitoBean private Clock authClock;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("delete from user_sessions");
		jdbcTemplate.update("delete from users");
		when(authClock.instant()).thenReturn(Instant.parse("2026-11-05T15:30:00Z"));
	}

	@Test
	void countsDistinctUsersAtInclusiveKoreanCalendarBoundaries() {
		user(1, "2026-11-05T15:00:00", null);
		user(2, "2026-11-05T14:59:59", null);
		user(3, "2026-11-05T10:00:00", null);
		user(4, "2026-10-01T00:00:00", null);
		user(5, "2026-10-01T00:00:00", null);
		user(6, "2026-11-05T15:10:00", "2026-11-05T15:20:00");
		user(7, "2026-10-01T00:00:00", null);
		session(1, "2026-11-05T15:00:00", false);
		session(1, "2026-11-05T15:20:00", false);
		session(2, "2026-11-05T14:59:59", false);
		session(3, "2026-11-05T10:00:00", true);
		session(4, "2026-10-30T15:00:00", false);
		session(5, "2026-10-30T14:59:59", false);
		session(6, "2026-11-05T15:10:00", false);

		assertThat(service.currentCounts()).isEqualTo(new UserActivityCounts(2, 5, 6, 1));
	}

	@Test
	void emptyTablesReturnZeroCounts() {
		assertThat(service.currentCounts()).isEqualTo(new UserActivityCounts(0, 0, 0, 0));
	}

	@Test
	void metricsAndScheduledTaskAreDisabledByDefault() {
		assertThat(context.getBeanProvider(UserActivityMetrics.class).getIfAvailable()).isNull();
		assertThat(scheduledTaskHolder.getScheduledTasks()).noneMatch(task ->
			task.getTask().toString().contains("UserActivityMetrics"));
		assertThat(registry.getMeters()).noneMatch(meter -> meter.getId().getName().startsWith("vium.users."));
	}

	private void user(long id, String createdAt, String deletedAt) {
		jdbcTemplate.update("""
			insert into users (id, email, display_name, created_at, updated_at, deleted_at)
			values (?, ?, ?, ?, ?, ?)
			""", id, "user" + id + "@example.com", "user" + id,
			timestamp(createdAt), timestamp(createdAt), deletedAt == null ? null : timestamp(deletedAt));
	}

	private void session(long userId, String issuedAt, boolean revoked) {
		LocalDateTime issued = LocalDateTime.parse(issuedAt);
		jdbcTemplate.update("""
			insert into user_sessions (user_id, refresh_token_hash, issued_at, expires_at, revoked_at)
			values (?, ?, ?, ?, ?)
			""", userId, UUID.randomUUID().toString(), Timestamp.valueOf(issued),
			Timestamp.valueOf(issued.plusDays(14)), revoked ? Timestamp.valueOf(issued.plusMinutes(1)) : null);
	}

	private static Timestamp timestamp(String value) {
		return Timestamp.valueOf(LocalDateTime.parse(value));
	}
}
