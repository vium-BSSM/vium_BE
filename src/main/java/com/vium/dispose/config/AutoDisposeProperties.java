package com.vium.dispose.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("disposal.auto-dispose")
public record AutoDisposeProperties(String cron, String timeZone) {
}
