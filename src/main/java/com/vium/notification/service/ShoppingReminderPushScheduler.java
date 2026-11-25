package com.vium.notification.service;

import com.vium.notification.config.FcmProperties;
import com.vium.notification.config.ShoppingReminderPushProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 장보기 리마인더 푸시의 정기 실행을 담당한다. push.shopping-reminder.enabled=true일 때만 빈으로 등록된다.
 */
@Component
@ConditionalOnProperty(name = "push.shopping-reminder.enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(ShoppingReminderPushProperties.class)
@RequiredArgsConstructor
@Slf4j
public class ShoppingReminderPushScheduler {

	private final ShoppingReminderPushService shoppingReminderPushService;
	private final ShoppingReminderPushProperties properties;
	private final FcmProperties fcmProperties;

	@PostConstruct
	void logActivation() {
		log.info("장보기 리마인더 푸시 스케줄러 활성화: cron={}, zone={}, fcm={}",
			properties.cron(), properties.timeZone(), fcmProperties.enabled());
	}

	@Scheduled(cron = "${push.shopping-reminder.cron}", zone = "${push.shopping-reminder.time-zone}")
	public void run() {
		int processed = shoppingReminderPushService.sendShoppingReminderPush();
		log.info("장보기 리마인더 푸시 배치 완료: 처리 사용자 {}명", processed);
	}
}
