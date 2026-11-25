package com.vium.notification.service;

import com.vium.notification.config.ConsumeCheckPushProperties;
import com.vium.notification.config.FcmProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "push.consume-check.enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(ConsumeCheckPushProperties.class)
@RequiredArgsConstructor
@Slf4j
public class ConsumeCheckPushScheduler {

	private final ConsumeCheckPushService consumeCheckPushService;
	private final ConsumeCheckPushProperties properties;
	private final FcmProperties fcmProperties;

	@PostConstruct
	void logActivation() {
		log.info("소진 확인 푸시 스케줄러 활성화: cron={}, zone={}, fcm={}",
			properties.cron(), properties.timeZone(), fcmProperties.enabled());
	}

	@Scheduled(cron = "${push.consume-check.cron}", zone = "${push.consume-check.time-zone}")
	public void run() {
		int processed = consumeCheckPushService.sendDailyConsumeCheckPush();
		log.info("소진 확인 푸시 배치 완료: 처리 사용자 {}명", processed);
	}
}
