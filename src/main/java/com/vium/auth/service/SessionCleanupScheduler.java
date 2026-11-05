package com.vium.auth.service;

import com.vium.auth.config.SessionCleanupProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 세션 정리 배치의 정기 실행을 담당한다.
 * auth.session-cleanup.enabled=true일 때만 이 컴포넌트 자체가 빈으로 등록되므로,
 * false(기본값)면 {@code @Scheduled} 작업 자체가 Spring에 등록되지 않는다.
 * {@link SessionCleanupService}는 조건과 무관하게 항상 존재해야 하므로 분리했다.
 */
@Component
@ConditionalOnProperty(name = "auth.session-cleanup.enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(SessionCleanupProperties.class)
@RequiredArgsConstructor
@Slf4j
public class SessionCleanupScheduler {

	private final SessionCleanupService sessionCleanupService;
	private final SessionCleanupProperties properties;

	@PostConstruct
	void logActivation() {
		log.info("세션 정리 스케줄러 활성화: cron={}, zone={}, retention={}, batchSize={}",
			properties.cron(), properties.timeZone(), properties.retention(), properties.batchSize());
	}

	@Scheduled(cron = "${auth.session-cleanup.cron}", zone = "${auth.session-cleanup.time-zone}")
	public void run() {
		sessionCleanupService.deleteExpiredSessions(properties.retention(), properties.batchSize());
	}
}
