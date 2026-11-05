package com.vium.auth.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("auth.session-cleanup")
public record SessionCleanupProperties(String cron, String timeZone, Duration retention, int batchSize) {
	public SessionCleanupProperties {
		if (retention == null || retention.isNegative() || retention.isZero() || batchSize < 1) {
			throw new IllegalArgumentException("auth.session-cleanup.retention and batch-size must be positive");
		}
	}
}
