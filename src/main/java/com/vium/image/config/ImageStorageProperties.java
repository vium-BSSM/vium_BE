package com.vium.image.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("images.storage")
public record ImageStorageProperties(@DefaultValue("false") boolean enabled,
	@DefaultValue("ap-northeast-2") String region,
	@DefaultValue("") String privateBucket, @DefaultValue("") String publicBucket,
	@DefaultValue("") String publicBaseUrl) {}
