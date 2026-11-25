package com.vium.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("push.consume-check")
public record ConsumeCheckPushProperties(String cron, String timeZone) {
}
