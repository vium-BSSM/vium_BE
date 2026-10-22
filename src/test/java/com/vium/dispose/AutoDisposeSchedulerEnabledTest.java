package com.vium.dispose;

import static org.assertj.core.api.Assertions.assertThat;

import com.vium.dispose.service.AutoDisposeScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * disposal.auto-dispose.enabled=true일 때만의 동작을 검증하기 위해
 * 별도 Spring 컨텍스트(다른 properties 조합)로 기동한다.
 */
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:auto-dispose-enabled-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
	"disposal.auto-dispose.enabled=true"
})
class AutoDisposeSchedulerEnabledTest {

	@Autowired
	private AutoDisposeScheduler autoDisposeScheduler;

	@Autowired
	private ScheduledTaskHolder scheduledTaskHolder;

	@Test
	void autoDisposeSchedulerBeanExistsWhenEnabled() {
		assertThat(autoDisposeScheduler).isNotNull();
	}

	@Test
	void scheduledCronTaskIsRegisteredWhenEnabled() {
		boolean cronTaskRegistered = scheduledTaskHolder.getScheduledTasks().stream()
			.anyMatch(task -> task.getTask().toString().contains("AutoDisposeScheduler"));
		assertThat(cronTaskRegistered).isTrue();
	}
}
