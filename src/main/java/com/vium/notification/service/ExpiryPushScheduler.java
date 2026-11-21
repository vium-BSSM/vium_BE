package com.vium.notification.service;

import com.vium.notification.config.ExpiryPushProperties;
import com.vium.notification.config.FcmProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 소비기한 임박 푸시의 정기 실행을 담당한다. push.expiry.enabled=true일 때만 이 컴포넌트 자체가
 * 빈으로 등록되므로, false(기본값)면 {@code @Scheduled} 작업 자체가 Spring에 등록되지 않는다.
 * {@link ExpiryPushService}는 조건과 무관하게 항상 존재해야 하므로 분리했다.
 */
@Component
@ConditionalOnProperty(name = "push.expiry.enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(ExpiryPushProperties.class)
@RequiredArgsConstructor
@Slf4j
public class ExpiryPushScheduler {

	private final ExpiryPushService expiryPushService;
	private final ExpiryPushProperties properties;
	private final FcmProperties fcmProperties;

	@PostConstruct
	void logActivation() {
		if (!fcmProperties.enabled()) {
			throw new IllegalStateException(
				"push.expiry.enabled=true 이지만 fcm.enabled=true가 아닙니다. "
					+ "실제 전송 없이 sent_at만 채워지는 것을 막기 위해 두 설정을 함께 켜야 합니다.");
		}
		log.info("소비기한 임박 푸시 스케줄러 활성화: cron={}, zone={}", properties.cron(), properties.timeZone());
	}

	@Scheduled(cron = "${push.expiry.cron}", zone = "${push.expiry.time-zone}")
	public void run() {
		expiryPushService.sendDailyExpiryPush();
	}
}
