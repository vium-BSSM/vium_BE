package com.vium.post.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("post.expiry")
public record PostExpiryProperties(
	@DefaultValue("false") boolean enabled,
	@DefaultValue("0 0 0 * * *") String cron,
	@DefaultValue("Asia/Seoul") String timeZone
) {
}
