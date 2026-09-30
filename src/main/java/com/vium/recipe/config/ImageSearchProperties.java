package com.vium.recipe.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "unsplash")
@Getter
@Setter
public class ImageSearchProperties {

	private String accessKey;
	private int timeoutSeconds = 3;
}
