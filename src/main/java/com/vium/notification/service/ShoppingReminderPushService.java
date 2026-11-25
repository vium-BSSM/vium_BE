package com.vium.notification.service;

import com.vium.notification.dto.ShoppingReminderPushCandidate;
import com.vium.notification.entity.Notification;
import com.vium.notification.repository.NotificationRepository;
import com.vium.notification.repository.ShoppingReminderPushQueryRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 장보기 리마인더는 사용자당 발송일 하루 한 건이다(notifications 유니크: user_id+scheduled_at,
 * type='shopping_reminder'). 행 생성은 사용자별 독립 트랜잭션(self 프록시)으로 처리하고,
 * 새 행을 SSE로 알리고 FCM 전송이 성공하거나 생략되면 sent_at을 기록한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ShoppingReminderPushService {

	static final String PUSH_TITLE = "장보기 전 냉장고 확인";

	private final ShoppingReminderPushQueryRepository queryRepository;
	private final NotificationRepository notificationRepository;
	private final BatchNotificationDelivery delivery;
	private final Clock clock;
	private final ObjectProvider<ShoppingReminderPushService> self;

	public int sendShoppingReminderPush() {
		LocalDateTime scheduledAt = LocalDate.now(clock).atStartOfDay();
		int processedUserCount = 0;
		for (ShoppingReminderPushCandidate candidate : queryRepository.findCandidates(scheduledAt)) {
			try {
				if (processUser(candidate, scheduledAt)) {
					processedUserCount++;
				}
			} catch (Exception e) {
				log.error("장보기 리마인더 푸시 처리 실패: userId={}", candidate.userId(), e);
			}
		}
		return processedUserCount;
	}

	private boolean processUser(ShoppingReminderPushCandidate candidate, LocalDateTime scheduledAt) {
		LocalDateTime now = LocalDateTime.now(clock);
		String body = body(candidate.activeItemCount());

		Long notificationId = candidate.existingNotificationId();
		boolean created = notificationId == null;
		if (created) {
			try {
				notificationId = self.getObject().createOne(candidate.userId(), body, scheduledAt, now).getId();
			} catch (DataIntegrityViolationException e) {
				log.info("이미 처리된 장보기 리마인더(동시 실행): userId={}", candidate.userId());
				return false;
			}
		}

		boolean completed = delivery.complete(candidate.userId(), created, candidate.pushEnabled(),
			"shopping_reminder", PUSH_TITLE, body);
		if (completed) {
			self.getObject().markSent(notificationId, LocalDateTime.now(clock));
		}
		return completed;
	}

	static String body(long activeItemCount) {
		return activeItemCount > 0
			? "냉장고에 재료 " + activeItemCount + "개가 있어요. 장보러 가기 전에 확인해 보세요"
			: "장보러 가기 전에 냉장고를 확인해 보세요";
	}

	@Transactional
	public Notification createOne(Long userId, String body, LocalDateTime scheduledAt, LocalDateTime now) {
		return notificationRepository.saveAndFlush(
			Notification.shoppingReminder(userId, PUSH_TITLE, body, scheduledAt, now));
	}

	@Transactional
	public void markSent(Long notificationId, LocalDateTime sentAt) {
		notificationRepository.findById(notificationId).ifPresent(n -> n.markSent(sentAt));
	}
}
