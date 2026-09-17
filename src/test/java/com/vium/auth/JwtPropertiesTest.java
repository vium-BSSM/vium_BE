package com.vium.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.vium.global.security.JwtProperties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class JwtPropertiesTest {
	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(PropertiesConfiguration.class)
		.withPropertyValues("security.jwt.secret=test-secret", "security.jwt.issuer=vium",
			"security.jwt.access-token-seconds=3600", "security.jwt.refresh-token-seconds=1209600");

	@ParameterizedTest
	@ValueSource(ints = {0, 60, 300})
	void acceptsSkewWithinBounds(int seconds) {
		contextRunner.withPropertyValues("security.jwt.clock-skew-seconds=" + seconds).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(JwtProperties.class).clockSkewSeconds()).isEqualTo(seconds);
		});
	}

	@ParameterizedTest
	@ValueSource(ints = {-1, 301, 86400})
	void rejectsSkewOutsideBoundsAtStartup(int seconds) {
		contextRunner.withPropertyValues("security.jwt.clock-skew-seconds=" + seconds).run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(BindValidationException.class);
		});
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(JwtProperties.class)
	static class PropertiesConfiguration { }
}
