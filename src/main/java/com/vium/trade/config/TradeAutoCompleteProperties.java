package com.vium.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("trade.auto-complete")
public record TradeAutoCompleteProperties(@DefaultValue("false") boolean enabled,
	@DefaultValue("0 */10 * * * *") String cron, @DefaultValue("Asia/Seoul") String timeZone) {}
