package com.vium.notification.service;

import com.vium.notification.dto.ShoppingReminderPushCandidate;
import com.vium.notification.entity.Notification;
import com.vium.notification.repository.NotificationRepository;
import com.vium.notification.repository.ShoppingReminderPushQueryRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 장보기 리마인더는 사용자당 발송일 하루 한 건이다(notifications 유니크: user_id+scheduled_at,
 * type='shopping_reminder'). 행 생성은 사용자별 독립 트랜잭션(self 프록시)으로 처리하고,
 * 저장/재시도 확정 -> FCM 전송(트랜잭션 밖) -> 성공분만 sent_at 갱신 순서는 다른 푸시 배치와 같다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ShoppingReminderPushService {

	static final String PUSH_TITLE = "장보기 전 냉장고 확인";

	private final ShoppingReminderPushQueryRepository queryRepository;
	private final NotificationRepository notificationRepository;
	private final PushDispatchService pushDispatchService;
	private final Clock clock;
	private final ObjectProvider<ShoppingReminderPushService> self;

	public int sendShoppingReminderPush() {
		LocalDateTime scheduledAt = LocalDate.now(clock).atStartOfDay();
		int sentUserCount = 0;
		for (ShoppingReminderPushCandidate candidate : queryRepository.findCandidates(scheduledAt)) {
			try {
				if (processUser(candidate, scheduledAt)) {
					sentUserCount++;
				}
			} catch (Exception e) {
				log.error("장보기 리마인더 푸시 처리 실패: userId={}", candidate.userId(), e);
			}
		}
		return sentUserCount;
	}

	private boolean processUser(ShoppingReminderPushCandidate candidate, LocalDateTime scheduledAt) {
		LocalDateTime now = LocalDateTime.now(clock);
		String body = body(candidate.activeItemCount());

		Long notificationId = candidate.existingNotificationId();
		if (notificationId == null) {
			try {
				notificationId = self.getObject().createOne(candidate.userId(), body, scheduledAt, now).getId();
			} catch (DataIntegrityViolationException e) {
				log.info("이미 처리된 장보기 리마인더(동시 실행): userId={}", candidate.userId());
				return false;
			}
		}

		boolean anySuccess = pushDispatchService.sendToUser(candidate.userId(), PUSH_TITLE, body,
			Map.of("type", "shopping_reminder"));
		if (anySuccess) {
			self.getObject().markSent(notificationId, now);
		}
		return anySuccess;
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
