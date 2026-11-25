package com.vium.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.vium.notification.config.FcmProperties;
import com.vium.notification.service.ConsumeCheckPushScheduler;
import com.vium.notification.service.ConsumeCheckPushService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

class ConsumeCheckPushSchedulerEnabledTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(ConsumeCheckPushScheduler.class, SchedulingConfig.class)
		.withBean(ConsumeCheckPushService.class, () -> mock(ConsumeCheckPushService.class))
		.withPropertyValues("push.consume-check.cron=0 0 20 * * *", "push.consume-check.time-zone=Asia/Seoul");

	@Test
	void schedulerBeanIsAbsentWhenDisabled() {
		runner.withBean(FcmProperties.class, () -> new FcmProperties(false, "", 10))
			.withPropertyValues("push.consume-check.enabled=false")
			.run(context -> assertThat(context).doesNotHaveBean(ConsumeCheckPushScheduler.class));
	}

	@Test
	void failsFastWhenFcmIsDisabled() {
		runner.withBean(FcmProperties.class, () -> new FcmProperties(false, "", 10))
			.withPropertyValues("push.consume-check.enabled=true")
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure())
					.hasRootCauseInstanceOf(IllegalStateException.class)
					.hasRootCauseMessage("push.consume-check.enabled=true 이지만 fcm.enabled=true가 아닙니다. "
						+ "실제 전송 없이 sent_at만 채워지는 것을 막기 위해 두 설정을 함께 켜야 합니다.");
			});
	}

	@Test
	void registersConfiguredCronWhenBothAreEnabled() {
		runner.withBean(FcmProperties.class, () -> new FcmProperties(true, "", 10))
			.withPropertyValues("push.consume-check.enabled=true")
			.run(context -> {
				assertThat(context).hasSingleBean(ConsumeCheckPushScheduler.class);
				var tasks = context.getBean(ScheduledTaskHolder.class).getScheduledTasks();
				assertThat(tasks).singleElement().satisfies(task ->
					assertThat((CronTask) task.getTask())
						.extracting(CronTask::getExpression).isEqualTo("0 0 20 * * *"));
			});
	}

	@TestConfiguration(proxyBeanMethods = false)
	@EnableScheduling
	static class SchedulingConfig {
	}
}
