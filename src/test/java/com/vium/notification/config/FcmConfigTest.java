package com.vium.notification.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.vium.notification.service.FcmPushClient;
import com.vium.notification.service.NoOpFcmPushClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class FcmConfigTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(FcmConfig.class);

	@Test
	void registersNoOpClientByDefault() {
		runner.run(context -> {
			assertThat(context).hasSingleBean(FcmPushClient.class);
			assertThat(context.getBean(FcmPushClient.class)).isInstanceOf(NoOpFcmPushClient.class);
		});
	}

	@Test
	void registersNoOpClientWhenExplicitlyDisabled() {
		runner.withPropertyValues("fcm.enabled=false").run(context ->
			assertThat(context.getBean(FcmPushClient.class)).isInstanceOf(NoOpFcmPushClient.class));
	}

	@Test
	void failsFastWhenEnabledWithoutCredentials() {
		runner.withPropertyValues("fcm.enabled=true", "fcm.credentials-json=").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
		});
	}
}
