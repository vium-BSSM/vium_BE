package com.vium.trade.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TradeNotificationConfig {

	@Bean
	@ConditionalOnMissingBean(TradeChatNotifier.class)
	public TradeChatNotifier tradeChatNotifier() {
		return new NoOpTradeChatNotifier();
	}
}
