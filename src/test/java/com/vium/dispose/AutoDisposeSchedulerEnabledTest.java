package com.vium.dispose;

import static org.assertj.core.api.Assertions.assertThat;

import com.vium.dispose.service.AutoDisposeScheduler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * disposal.auto-dispose.enabled=true일 때만의 동작을 검증하기 위해
 * 별도 Spring 컨텍스트(다른 properties 조합)로 기동한다.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:auto-dispose-enabled-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
	"disposal.auto-dispose.enabled=true",
	"disposal.auto-dispose.cron=0 30 4 * * *",
	"disposal.auto-dispose.time-zone=Asia/Seoul"
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
		assertThat(scheduledTaskHolder.getScheduledTasks().stream()
			.filter(task -> task.getTask().toString().contains("AutoDisposeScheduler"))
			.map(task -> ((CronTask) task.getTask()).getExpression()))
			.containsExactly("0 30 4 * * *");
	}

	@AfterAll
	static void logsConfiguredScheduleOnce(CapturedOutput output) {
		assertThat(output.getOut())
			.containsOnlyOnce("자동 폐기 스케줄러 활성화: cron=0 30 4 * * *, zone=Asia/Seoul");
	}
}
