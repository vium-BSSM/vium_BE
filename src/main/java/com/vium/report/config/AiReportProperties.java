package com.vium.report.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ai-report")
public record AiReportProperties(@DefaultValue("5") int cost) {
	public AiReportProperties {
		if (cost < 1) {
			throw new IllegalArgumentException("ai-report.cost must be positive");
		}
	}
}
