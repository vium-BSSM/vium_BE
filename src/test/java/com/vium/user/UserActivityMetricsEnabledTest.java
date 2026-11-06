package com.vium.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.vium.user.dto.UserActivityCounts;
import com.vium.user.service.UserActivityMetrics;
import com.vium.user.service.UserActivityService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:user-activity-enabled-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
	"metrics.user-activity.enabled=true"
})
class UserActivityMetricsEnabledTest {
	@Autowired private UserActivityMetrics metrics;
	@Autowired private MeterRegistry registry;
	@Autowired private ScheduledTaskHolder scheduledTaskHolder;
	@MockitoBean private UserActivityService service;

	@BeforeEach
	void setUp() {
		when(service.currentCounts()).thenReturn(new UserActivityCounts(3, 10, 42, 1));
	}

	@Test
	void registersFixedDelayTaskAndPublishesToInjectedRegistry() {
		assertThat(metrics).isNotNull();
		assertThat(scheduledTaskHolder.getScheduledTasks().stream()
			.filter(task -> task.getTask().toString().contains("UserActivityMetrics")))
			.singleElement().satisfies(task -> {
				assertThat(task.getTask()).isInstanceOf(FixedDelayTask.class);
				FixedDelayTask fixedDelay = (FixedDelayTask) task.getTask();
				assertThat(fixedDelay.getIntervalDuration()).isEqualTo(Duration.ofMinutes(5));
				assertThat(fixedDelay.getInitialDelayDuration()).isEqualTo(Duration.ofSeconds(10));
			});
		metrics.refresh();
		assertThat(registry.get("vium.users.active").tag("period", "today").gauge().value()).isEqualTo(3);
	}

	@AfterAll
	static void logsActivationOnce(CapturedOutput output) {
		assertThat(output.getOut()).containsOnlyOnce("사용자 활동 메트릭 활성화: 갱신 간격 5분");
	}
}
