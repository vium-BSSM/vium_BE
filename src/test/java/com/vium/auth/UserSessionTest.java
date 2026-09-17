package com.vium.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.vium.auth.entity.UserSession;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UserSessionTest {
	@ParameterizedTest
	@ValueSource(strings = {"UTC", "Asia/Seoul", "America/New_York"})
	void expirationUsesUtcRegardlessOfClockZone(String zone) {
		Instant issuedAt = Instant.parse("2026-09-17T00:00:00Z");
		Instant expiresAt = issuedAt.plusSeconds(1209600);
		Clock clock = Clock.fixed(issuedAt, ZoneId.of(zone));
		UserSession session = UserSession.create(42L, "test-refresh-token", clock.instant(), expiresAt);

		assertThat(session.getIssuedAt()).isEqualTo(LocalDateTime.parse("2026-09-17T00:00:00"));
		assertThat(session.getExpiresAt()).isEqualTo(LocalDateTime.parse("2026-10-01T00:00:00"));
		assertThat(session.isExpired(clock.instant())).isFalse();
		assertThat(session.isExpired(expiresAt.minusNanos(1))).isFalse();
		assertThat(session.isExpired(expiresAt)).isTrue();
		assertThat(session.isExpired(expiresAt.plusSeconds(1))).isTrue();
	}
}
