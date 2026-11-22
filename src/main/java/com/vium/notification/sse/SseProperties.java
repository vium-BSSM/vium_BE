package com.vium.notification.sse;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("sse")
public record SseProperties(
	@DefaultValue("25") int heartbeatIntervalSeconds,
	@DefaultValue("3") int maxConnectionsPerUser,
	@DefaultValue("1800") int maxConnectionAgeSeconds,
	Executor executor) {

	public SseProperties {
		if (executor == null) {
			executor = new Executor(2, 4, 100);
		}
	}

	public record Executor(
		@DefaultValue("2") int coreSize,
		@DefaultValue("4") int maxSize,
		@DefaultValue("100") int queueCapacity) {
	}
}
