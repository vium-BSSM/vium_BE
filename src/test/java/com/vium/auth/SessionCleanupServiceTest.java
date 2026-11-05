package com.vium.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vium.auth.config.SessionCleanupProperties;
import com.vium.auth.repository.UserSessionRepository;
import com.vium.auth.service.SessionCleanupService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class SessionCleanupServiceTest {
	@Test
	void propagatesFailureAndLogsPreviouslyDeletedCount(CapturedOutput output) {
		UserSessionRepository repository = mock(UserSessionRepository.class);
		Instant now = Instant.parse("2026-11-01T00:00:00Z");
		LocalDateTime cutoff = LocalDateTime.ofInstant(now.minus(Duration.ofDays(30)), ZoneOffset.UTC);
		RuntimeException failure = new IllegalStateException("delete failed");
		when(repository.deleteExpiredBefore(cutoff, 2)).thenReturn(2).thenThrow(failure);
		SessionCleanupService service = new SessionCleanupService(repository, Clock.fixed(now, ZoneOffset.UTC));

		assertThatThrownBy(() -> service.deleteExpiredSessions(Duration.ofDays(30), 2)).isSameAs(failure);
		verify(repository, times(2)).deleteExpiredBefore(cutoff, 2);
		assertThat(output.getOut()).contains("세션 정리 배치 실패: 삭제 2건 후 중단");
	}

	@Test
	void rejectsInvalidRetentionAndBatchSizeBeforeDeleting() {
		UserSessionRepository repository = mock(UserSessionRepository.class);
		SessionCleanupService service = new SessionCleanupService(repository, Clock.systemUTC());
		for (Duration retention : new Duration[] {null, Duration.ZERO, Duration.ofDays(-1)}) {
			assertThatThrownBy(() -> service.deleteExpiredSessions(retention, 2))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> new SessionCleanupProperties("0 30 4 * * *", "Asia/Seoul", retention, 2))
				.isInstanceOf(IllegalArgumentException.class);
		}
		for (int batchSize : new int[] {0, -1}) {
			assertThatThrownBy(() -> service.deleteExpiredSessions(Duration.ofDays(30), batchSize))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> new SessionCleanupProperties("0 30 4 * * *", "Asia/Seoul", Duration.ofDays(30), batchSize))
				.isInstanceOf(IllegalArgumentException.class);
		}
		verifyNoInteractions(repository);
	}
}
