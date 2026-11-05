package com.vium.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.vium.auth.service.SessionCleanupScheduler;
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
 * auth.session-cleanup.enabled=true일 때만의 동작을 검증하기 위해
 * 별도 Spring 컨텍스트(다른 properties 조합)로 기동한다.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:session-cleanup-enabled-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
	"auth.session-cleanup.enabled=true",
	"auth.session-cleanup.retention=30d",
	"auth.session-cleanup.batch-size=1000",
	"auth.session-cleanup.cron=0 0 5 * * *",
	"auth.session-cleanup.time-zone=Asia/Seoul"
})
class SessionCleanupSchedulerEnabledTest {

	@Autowired
	private SessionCleanupScheduler sessionCleanupScheduler;

	@Autowired
	private ScheduledTaskHolder scheduledTaskHolder;

	@Test
	void sessionCleanupSchedulerBeanExistsWhenEnabled() {
		assertThat(sessionCleanupScheduler).isNotNull();
	}

	@Test
	void scheduledCronTaskIsRegisteredWhenEnabled() {
		assertThat(scheduledTaskHolder.getScheduledTasks().stream()
			.filter(task -> task.getTask().toString().contains("SessionCleanupScheduler"))
			.map(task -> ((CronTask) task.getTask()).getExpression()))
			.containsExactly("0 0 5 * * *");
	}

	@AfterAll
	static void logsConfiguredScheduleOnce(CapturedOutput output) {
		assertThat(output.getOut())
			.containsOnlyOnce("세션 정리 스케줄러 활성화: cron=0 0 5 * * *, zone=Asia/Seoul, retention=PT720H, batchSize=1000");
	}
}
