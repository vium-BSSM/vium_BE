package com.vium.global;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import io.micrometer.registry.otlp.CompressionMode;
import io.micrometer.registry.otlp.OtlpConfig;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import io.micrometer.registry.otlp.OtlpMetricsSender;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.otlp.OtlpMetricsExportAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.FileSystemResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OtlpMetricsExportTest {

    private ApplicationContextRunner runner(String configuration) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        MetricsAutoConfiguration.class, OtlpMetricsExportAutoConfiguration.class))
                .withInitializer(context -> {
                    try {
                        new YamlPropertySourceLoader().load("application", new FileSystemResource(configuration))
                                .forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
                    } catch (IOException exception) {
                        throw new IllegalStateException(exception);
                    }
                });
    }

    @Test
    void mainConfigurationDisablesExportByDefault() {
        runner("src/main/resources/application.yml").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(OtlpMeterRegistry.class);
        });
    }

    @Test
    void explicitDisableDoesNotCreateRegistry() {
        runner("src/main/resources/application.yml")
                .withPropertyValues("OTLP_METRICS_ENABLED=false")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(OtlpMeterRegistry.class));
    }

    @Test
    void testConfigurationDisablesExportEvenWhenEnvironmentEnablesIt() {
        runner("src/test/resources/application.yml")
                .withPropertyValues("OTLP_METRICS_ENABLED=true")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(OtlpMeterRegistry.class));
    }

    @Test
    void enabledExportBindsProductionSettingsAndTagsAndToleratesSendFailure() throws Exception {
        // Exercise publication on close without opening any network connection.
        OtlpMetricsSender sender = mock(OtlpMetricsSender.class);
        doThrow(new IOException("Simulated unavailable collector")).when(sender).send(any());
        assertThatCode(() -> runner("src/main/resources/application.yml")
                .withBean(OtlpMetricsSender.class, () -> sender)
                .withPropertyValues("OTLP_METRICS_ENABLED=true",
                        "OTLP_METRICS_URL=http://localhost:1/v1/metrics",
                        "OTLP_METRICS_AUTHORIZATION=Basic dGVzdA==", "DEPLOY_ENV=prod")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(OtlpMeterRegistry.class);
                    OtlpConfig config = context.getBean(OtlpConfig.class);
                    assertThat(config.url()).isEqualTo("http://localhost:1/v1/metrics");
                    assertThat(config.headers()).containsEntry("Authorization", "Basic dGVzdA==");
                    assertThat(config.baseTimeUnit()).isEqualTo(TimeUnit.SECONDS);
                    assertThat(config.step()).isEqualTo(Duration.ofMinutes(1));
                    assertThat(config.compressionMode()).isEqualTo(CompressionMode.GZIP);
                    assertThat(config.resourceAttributes()).containsEntry("service.name", "vium-be")
                            .containsEntry("deployment.environment.name", "prod");
                    var timer = context.getBean(OtlpMeterRegistry.class).timer("http.server.requests");
                    timer.record(Duration.ofMillis(100));
                    assertThat(timer.getId().getTag("application")).isEqualTo("vium-be");
                    assertThat(timer.getId().getTag("env")).isEqualTo("prod");
                    // Seven SLO bounds plus the +Inf bucket.
                    assertThat(timer.takeSnapshot().histogramCounts()).hasSize(8);
                })).doesNotThrowAnyException();
        verify(sender).send(any());
    }
}
