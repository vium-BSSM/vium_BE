package com.vium.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.vium.auth.entity.UserSession;
import com.vium.auth.repository.UserSessionRepository;
import com.vium.auth.service.SessionCleanupScheduler;
import com.vium.auth.service.SessionCleanupService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:session-cleanup-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class SessionCleanupIntegrationTest {
	private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
	private static final Duration RETENTION = Duration.ofDays(30);
	private static final Instant CUTOFF = NOW.minus(RETENTION);

	@Autowired private UserSessionRepository repository;
	@Autowired private SessionCleanupService service;
	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private ApplicationContext context;
	@Autowired private ScheduledTaskHolder scheduledTaskHolder;
	@MockitoBean private Clock authClock;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("delete from user_sessions");
		when(authClock.instant()).thenReturn(NOW);
	}

	@Test
	void deletesOnlySessionsExpiredBeforeCutoff(CapturedOutput output) {
		long active = session(NOW.plus(Duration.ofDays(1)), null);
		long recent = session(NOW.minus(Duration.ofDays(1)), null);
		long boundary = session(CUTOFF, null);
		session(CUTOFF.minusSeconds(1), null);
		session(NOW.minus(Duration.ofDays(31)), NOW.minus(Duration.ofDays(40)));

		assertThat(service.deleteExpiredSessions(RETENTION, 1000)).isEqualTo(2);
		assertThat(repository.findAll()).extracting(UserSession::getId)
			.containsExactlyInAnyOrder(active, recent, boundary);
		assertThat(output.getOut()).contains("세션 정리 배치 완료: 삭제 2건");
	}

	@Test
	void preservesRevokedSessionsUntilExpiryRetentionPasses() {
		long active = session(NOW.plus(Duration.ofDays(3)), NOW.minus(Duration.ofDays(11)));
		long recent = session(NOW.minus(Duration.ofDays(20)), NOW.minus(Duration.ofDays(25)));
		long oldRevocation = session(CUTOFF, NOW.minus(Duration.ofDays(40)));
		assertThat(service.deleteExpiredSessions(RETENTION, 1000)).isZero();
		assertThat(repository.findAll()).extracting(UserSession::getId)
			.containsExactlyInAnyOrder(active, recent, oldRevocation);
	}

	@Test
	void repeatsChunksUntilPartialBatch() {
		for (int i = 0; i < 5; i++) session(CUTOFF.minusSeconds(1), null);
		long active = session(NOW.plusSeconds(60), null);
		assertThat(service.deleteExpiredSessions(RETENTION, 2)).isEqualTo(5);
		assertThat(repository.findAll()).extracting(UserSession::getId).containsExactly(active);
	}

	@Test
	void stopsAfterEmptyBatchForExactMultiple() {
		for (int i = 0; i < 4; i++) session(CUTOFF.minusSeconds(1), null);
		assertThat(service.deleteExpiredSessions(RETENTION, 2)).isEqualTo(4);
		assertThat(repository.count()).isZero();
	}

	@Test
	void repositoryLimitsEachDeletion() {
		for (int i = 0; i < 3; i++) session(CUTOFF.minusSeconds(1), null);
		assertThat(repository.deleteExpiredBefore(LocalDateTime.ofInstant(CUTOFF, ZoneOffset.UTC), 2))
			.isEqualTo(2);
		assertThat(repository.count()).isEqualTo(1);
	}

	@Test
	void schedulerIsDisabledByDefault() {
		assertThat(context.getBeanProvider(SessionCleanupScheduler.class).getIfAvailable()).isNull();
		assertThat(scheduledTaskHolder.getScheduledTasks()).noneMatch(task ->
			task.getTask().toString().contains("SessionCleanupScheduler"));
	}

	private long session(Instant expiresAt, Instant revokedAt) {
		long id = repository.saveAndFlush(UserSession.create(42L, UUID.randomUUID().toString(),
			expiresAt.minus(Duration.ofDays(14)), expiresAt)).getId();
		if (revokedAt != null) {
			jdbcTemplate.update("update user_sessions set revoked_at = ? where id = ?",
				LocalDateTime.ofInstant(revokedAt, ZoneOffset.UTC), id);
		}
		return id;
	}
}
