package com.vium.trade.service;

import com.vium.trade.config.TradeAutoCompleteProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "trade.auto-complete.enabled", havingValue = "true")
@EnableConfigurationProperties(TradeAutoCompleteProperties.class)
@RequiredArgsConstructor
@Slf4j
public class TradeAutoCompleteScheduler {
	private final TradeAutoCompleteService service;
	private final TradeAutoCompleteProperties properties;

	@PostConstruct
	void logActivation() {
		log.info("거래 자동 완료 스케줄러 활성화: cron={}, zone={}", properties.cron(), properties.timeZone());
	}

	@Scheduled(cron = "${trade.auto-complete.cron:0 */10 * * * *}", zone = "${trade.auto-complete.time-zone:Asia/Seoul}")
	public void run() {
		service.completeDueTrades();
		service.notifySoonTrades();
	}
}
