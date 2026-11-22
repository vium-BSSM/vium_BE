package com.vium.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.vium.notification.config.FcmProperties;
import com.vium.notification.service.ExpiryPushScheduler;
import com.vium.notification.service.ExpiryPushService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * push.expiry.enabled x fcm.enabled 조합별 ExpiryPushScheduler 빈 등록/기동 실패 여부를 검증한다.
 * 실제 FCM 자격 증명이 필요 없는, 설정 조합 자체에 대한 가벼운 검증이라 전체 @SpringBootTest 대신
 * ApplicationContextRunner로 ExpiryPushScheduler만 올린다(ImageStorageConfigTest/FcmConfigTest와 동일한 패턴).
 */
class ExpiryPushSchedulerConfigValidationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(ExpiryPushScheduler.class)
		.withBean(ExpiryPushService.class, () -> mock(ExpiryPushService.class));

	@Test
	void noSchedulerBeanWhenBothDisabled() {
		runner.withBean(FcmProperties.class, () -> new FcmProperties(false, "", 10))
			.run(context -> assertThat(context).doesNotHaveBean(ExpiryPushScheduler.class));
	}

	@Test
	void schedulerBeanExistsAndStartsWhenBothEnabled() {
		runner.withBean(FcmProperties.class, () -> new FcmProperties(true, "whatever", 10))
			.withPropertyValues(
				"push.expiry.enabled=true",
				"push.expiry.cron=0 0 9 * * *",
				"push.expiry.time-zone=Asia/Seoul")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(ExpiryPushScheduler.class);
			});
	}

	@Test
	void failsFastWhenPushEnabledButFcmNotEnabled() {
		runner.withBean(FcmProperties.class, () -> new FcmProperties(false, "", 10))
			.withPropertyValues(
				"push.expiry.enabled=true",
				"push.expiry.cron=0 0 9 * * *",
				"push.expiry.time-zone=Asia/Seoul")
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
			});
	}
}
