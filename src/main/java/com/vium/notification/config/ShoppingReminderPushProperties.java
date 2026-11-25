package com.vium.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("push.shopping-reminder")
public record ShoppingReminderPushProperties(String cron, String timeZone) {
}
