package com.vium.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vium.user.dto.UserActivityCounts;
import com.vium.user.service.UserActivityMetrics;
import com.vium.user.service.UserActivityService;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class UserActivityMetricsTest {
	private final UserActivityService service = mock(UserActivityService.class);
	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
	private final UserActivityMetrics metrics = new UserActivityMetrics(service, registry);

	@AfterEach
	void closeRegistry() {
		registry.close();
	}

	@Test
	void doesNotRegisterGaugesBeforeFirstSuccess() {
		assertThat(registry.getMeters()).isEmpty();
		assertThat(registry.find("vium.users.active").gauges()).isEmpty();
	}

	@Test
	void registersFourGaugesWithOnlyFixedPeriodTags() {
		when(service.currentCounts()).thenReturn(new UserActivityCounts(3, 10, 42, 1));
		metrics.refresh();
		assertCounts(3, 10, 42, 1);
		assertThat(registry.getMeters()).allSatisfy(meter -> {
			assertThat(meter.getId().getBaseUnit()).isNull();
			assertThat(meter.getId().getDescription()).isNotBlank();
			if (meter.getId().getName().equals("vium.users.registered")) {
				assertThat(meter.getId().getTags()).isEmpty();
			} else {
				assertThat(meter.getId().getTags()).extracting(Tag::getKey).containsExactly("period");
			}
		});
		// Reading gauge callbacks must never query the database again.
		verify(service, times(1)).currentCounts();
	}

	@Test
	void preservesAllValuesOnFailureAndRecoversWithoutDuplicateMeters(CapturedOutput output) {
		RuntimeException failure = new IllegalStateException("database unavailable");
		when(service.currentCounts()).thenReturn(new UserActivityCounts(3, 10, 42, 1))
			.thenThrow(failure).thenReturn(new UserActivityCounts(5, 12, 44, 3));
		metrics.refresh();
		assertThatThrownBy(metrics::refresh).isSameAs(failure);
		assertCounts(3, 10, 42, 1);
		assertThat(output.getOut()).contains("사용자 활동 메트릭 갱신 실패: 이전 값을 유지한다");
		metrics.refresh();
		assertCounts(5, 12, 44, 3);
	}

	@Test
	void firstFailureLeavesNoMetersAndNextSuccessRegistersThem() {
		RuntimeException failure = new IllegalStateException("database unavailable");
		when(service.currentCounts()).thenThrow(failure).thenReturn(new UserActivityCounts(0, 0, 0, 0));
		assertThatThrownBy(metrics::refresh).isSameAs(failure);
		assertThat(registry.getMeters()).isEmpty();
		metrics.refresh();
		assertCounts(0, 0, 0, 0);
	}

	private void assertCounts(long today, long week, long registered, long signups) {
		assertThat(registry.getMeters()).hasSize(4);
		assertThat(registry.get("vium.users.active").tag("period", "today").gauge().value()).isEqualTo(today);
		assertThat(registry.get("vium.users.active").tag("period", "7d").gauge().value()).isEqualTo(week);
		assertThat(registry.get("vium.users.registered").gauge().value()).isEqualTo(registered);
		assertThat(registry.get("vium.users.signups").tag("period", "today").gauge().value()).isEqualTo(signups);
	}
}
