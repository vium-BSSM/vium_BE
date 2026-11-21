package com.vium.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("fcm")
public record FcmProperties(boolean enabled, String credentialsJson, int timeoutSeconds) {
}
