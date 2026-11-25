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
		if (!fcmProperties.enabled()) {
			throw new IllegalStateException(
				"push.consume-check.enabled=true 이지만 fcm.enabled=true가 아닙니다. "
					+ "실제 전송 없이 sent_at만 채워지는 것을 막기 위해 두 설정을 함께 켜야 합니다.");
		}
		log.info("소진 확인 푸시 스케줄러 활성화: cron={}, zone={}", properties.cron(), properties.timeZone());
	}

	@Scheduled(cron = "${push.consume-check.cron}", zone = "${push.consume-check.time-zone}")
	public void run() {
		consumeCheckPushService.sendDailyConsumeCheckPush();
	}
}
