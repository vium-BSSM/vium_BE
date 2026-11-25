package com.vium.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.vium.notification.config.FcmProperties;
import com.vium.notification.service.ShoppingReminderPushScheduler;
import com.vium.notification.service.ShoppingReminderPushService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

@ExtendWith(OutputCaptureExtension.class)
class ShoppingReminderPushSchedulerEnabledTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(ShoppingReminderPushScheduler.class, SchedulingConfig.class)
		.withBean(ShoppingReminderPushService.class, () -> mock(ShoppingReminderPushService.class))
		.withPropertyValues("push.shopping-reminder.cron=0 0 10 * * SAT", "push.shopping-reminder.time-zone=Asia/Seoul");

	@Test
	void schedulerBeanIsAbsentWhenDisabled() {
		runner.withBean(FcmProperties.class, () -> new FcmProperties(false, "", 10))
			.withPropertyValues("push.shopping-reminder.enabled=false")
			.run(context -> assertThat(context).doesNotHaveBean(ShoppingReminderPushScheduler.class));
	}

	@Test
	void startsWhenFcmIsDisabled(CapturedOutput output) {
		runner.withBean(FcmProperties.class, () -> new FcmProperties(false, "", 10))
			.withPropertyValues("push.shopping-reminder.enabled=true")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(ShoppingReminderPushScheduler.class);
				assertThat(output.getOut()).contains("장보기 리마인더 푸시 스케줄러 활성화: cron=0 0 10 * * SAT, zone=Asia/Seoul, fcm=false");
			});
	}

	@Test
	void registersConfiguredCronWhenBothAreEnabled() {
		runner.withBean(FcmProperties.class, () -> new FcmProperties(true, "", 10))
			.withPropertyValues("push.shopping-reminder.enabled=true")
			.run(context -> {
				assertThat(context).hasSingleBean(ShoppingReminderPushScheduler.class);
				var tasks = context.getBean(ScheduledTaskHolder.class).getScheduledTasks();
				assertThat(tasks).singleElement().satisfies(task ->
					assertThat((CronTask) task.getTask())
						.extracting(CronTask::getExpression).isEqualTo("0 0 10 * * SAT"));
			});
	}

	@TestConfiguration(proxyBeanMethods = false)
	@EnableScheduling
	static class SchedulingConfig {
	}
}
