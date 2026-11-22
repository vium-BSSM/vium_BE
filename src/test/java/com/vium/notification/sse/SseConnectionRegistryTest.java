package com.vium.notification.sse;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 레지스트리의 순수 자바 단위 테스트. handler가 없는 상태의 SseEmitter는 complete()를 호출해도
 * onCompletion 콜백을 실행하지 않으므로(ResponseBodyEmitter.complete() 바이트코드 확인 — handler가
 * null이면 completionCallback을 호출하지 않는다), 사용자당 연결 상한 초과 시 가장 오래된 연결을
 * 즉시 pollFirst()로 구조에서 직접 제거하는 부분만 sizeForTest()로 확인한다. tick()의 최대 수명 종료는
 * onCompletion에 의존하므로(실제 handler가 필요) MockMvc 기반 통합 테스트(SseHeartbeatAndMaxAgeIntegrationTest)에서
 * 검증한다.
 */
class SseConnectionRegistryTest {

	private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));

	private SseConnectionRegistry registry(int maxConnectionsPerUser, int maxConnectionAgeSeconds) {
		SseProperties properties = new SseProperties(25, maxConnectionsPerUser, maxConnectionAgeSeconds,
			new SseProperties.Executor(2, 4, 100));
		return new SseConnectionRegistry(properties, clock);
	}

	@Test
	void registerReturnsEmitterWithoutThrowing() {
		SseEmitter emitter = registry(3, 1800).register(1L);
		assertThat(emitter).isNotNull();
	}

	@Test
	void exceedingMaxConnectionsPerUserEvictsOldestAndCapsSize() {
		SseConnectionRegistry registry = registry(2, 1800);
		registry.register(1L);
		registry.register(1L);

		registry.register(1L);

		assertThat(registry.sizeForTest(1L)).isEqualTo(2);
	}

	@Test
	void differentUsersDoNotShareTheConnectionCap() {
		SseConnectionRegistry registry = registry(1, 1800);
		registry.register(1L);

		registry.register(2L);

		assertThat(registry.sizeForTest(1L)).isEqualTo(1);
		assertThat(registry.sizeForTest(2L)).isEqualTo(1);
	}

	@Test
	void broadcastToUnknownUserDoesNothing() {
		SseConnectionRegistry registry = registry(3, 1800);
		registry.broadcast(999L, "chat-message", "x");
	}

	@Test
	void clockAdvancesIndependentlyOfWallClock() {
		Instant before = clock.instant();
		clock.advance(Duration.ofSeconds(100));
		assertThat(clock.instant()).isEqualTo(before.plusSeconds(100));
	}

	private static final class MutableClock extends Clock {
		private Instant instant;

		MutableClock(Instant instant) {
			this.instant = instant;
		}

		void advance(Duration duration) {
			instant = instant.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneId.of("UTC");
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return instant;
		}
	}
}
