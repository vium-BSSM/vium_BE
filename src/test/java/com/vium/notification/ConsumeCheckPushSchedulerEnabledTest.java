package com.vium.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.vium.notification.config.FcmProperties;
import com.vium.notification.service.ConsumeCheckPushScheduler;
import com.vium.notification.service.ConsumeCheckPushService;
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
	void startsWhenFcmIsDisabled(CapturedOutput output) {
		runner.withBean(FcmProperties.class, () -> new FcmProperties(false, "", 10))
			.withPropertyValues("push.consume-check.enabled=true")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(ConsumeCheckPushScheduler.class);
				assertThat(output.getOut()).contains("소진 확인 푸시 스케줄러 활성화: cron=0 0 20 * * *, zone=Asia/Seoul, fcm=false");
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
