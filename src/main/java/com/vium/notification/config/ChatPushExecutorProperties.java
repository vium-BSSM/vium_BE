package com.vium.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("push.chat.executor")
public record ChatPushExecutorProperties(
	@DefaultValue("2") int coreSize,
	@DefaultValue("4") int maxSize,
	@DefaultValue("100") int queueCapacity) {
}
