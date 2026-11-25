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
		if (!fcmProperties.enabled()) {
			throw new IllegalStateException(
				"push.shopping-reminder.enabled=true 이지만 fcm.enabled=true가 아닙니다. "
					+ "실제 전송 없이 sent_at만 채워지는 것을 막기 위해 두 설정을 함께 켜야 합니다.");
		}
		log.info("장보기 리마인더 푸시 스케줄러 활성화: cron={}, zone={}", properties.cron(), properties.timeZone());
	}

	@Scheduled(cron = "${push.shopping-reminder.cron}", zone = "${push.shopping-reminder.time-zone}")
	public void run() {
		int sent = shoppingReminderPushService.sendShoppingReminderPush();
		log.info("장보기 리마인더 푸시 배치 완료: 발송 사용자 {}명", sent);
	}
}
