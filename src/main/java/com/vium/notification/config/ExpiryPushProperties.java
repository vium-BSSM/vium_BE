package com.vium.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("push.expiry")
public record ExpiryPushProperties(String cron, String timeZone) {
}
