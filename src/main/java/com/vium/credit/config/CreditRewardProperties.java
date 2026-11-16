package com.vium.credit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("credit.daily-reward")
public record CreditRewardProperties(@DefaultValue("1") int amount) {
	public CreditRewardProperties {
		if (amount < 1 || amount > 8) {
			throw new IllegalArgumentException("credit.daily-reward.amount must be between 1 and 8");
		}
	}
}
