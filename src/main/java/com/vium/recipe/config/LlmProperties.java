package com.vium.recipe.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "gemini")
@Getter
@Setter
public class LlmProperties {

	private String apiKey;
	private String model;
	private int timeoutSeconds = 60;
}
