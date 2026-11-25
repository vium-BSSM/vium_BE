package com.vium.notification.service;

import com.vium.notification.dto.ExpiryPushCandidate;
import com.vium.notification.entity.Notification;
import com.vium.notification.repository.ExpiryPushQueryRepository;
import com.vium.notification.repository.NotificationRepository;
import com.vium.user.service.UserSettingsService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재고당 expiry 알림은 최초 1회만 만든다(notifications 유니크: user_id+inventory_item_id, type='expiry').
 * 이미 전달 처리된(sent_at NOT NULL) 재고는 ExpiryPushQueryRepository가 후보에서 제외하고,
 * sent_at이 NULL인 기존 행(이전 전송 실패)은 existingNotificationId로 돌려줘 같은 행으로 재시도한다.
 * 새 행 생성(createOne)만 재고별 독립 트랜잭션으로 처리한다 — 재시도는 INSERT가 없어 유니크 위반 자체가
 * 나지 않는다. createOne/markSent를 self(프록시)로 호출하는 이유는 PushTokenService와 같다: 같은
 * 트랜잭션에서 유니크 위반이 나면 PostgreSQL이 그 트랜잭션 전체를 중단 상태로 만들어 같은 사용자의
 * 다른 재고 저장까지 막으므로, 재고 1건마다 독립된 트랜잭션이어야 한다. 토큰 순회 전송과 무효 토큰
 * 삭제는 PushDispatchService에 위임한다.
 * 알림 저장 -> 새 행 SSE -> FCM 전송 여부 판정 -> 성공 또는 생략 시 sent_at 갱신 순으로 처리한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExpiryPushService {

	private static final String PUSH_TITLE = "소비기한 임박 알림";

	private final ExpiryPushQueryRepository expiryPushQueryRepository;
	private final NotificationRepository notificationRepository;
	private final BatchNotificationDelivery delivery;
	private final Clock clock;
	private final ObjectProvider<ExpiryPushService> self;

	public int sendDailyExpiryPush() {
		LocalDate today = LocalDate.now(clock);
		LocalDate lowerBound = today;
		LocalDate upperBound = today.plusDays(UserSettingsService.EXPIRY_ALERT_DAYS_MAX);

		Map<Long, List<ExpiryPushCandidate>> byUser = expiryPushQueryRepository
			.findCandidates(lowerBound, upperBound).stream()
			.filter(c -> !c.expiresOn().isAfter(today.plusDays(c.expiryAlertDays())))
			.collect(Collectors.groupingBy(ExpiryPushCandidate::userId, LinkedHashMap::new, Collectors.toList()));

		int processedUserCount = 0;
		for (var entry : byUser.entrySet()) {
			Long userId = entry.getKey();
			try {
				if (processUser(userId, entry.getValue())) {
					processedUserCount++;
				}
			} catch (Exception e) {
				log.error("소비기한 임박 푸시 처리 실패: userId={}", userId, e);
			}
		}
		return processedUserCount;
	}

	private boolean processUser(Long userId, List<ExpiryPushCandidate> items) {
		LocalDateTime now = LocalDateTime.now(clock);

		List<Long> targetIds = new ArrayList<>();
		boolean created = false;
		for (ExpiryPushCandidate item : items) {
			if (item.existingNotificationId() != null) {
				targetIds.add(item.existingNotificationId());
				continue;
			}
			try {
				targetIds.add(self.getObject().createOne(userId, item, now).getId());
				created = true;
			} catch (DataIntegrityViolationException e) {
				log.info("이미 처리된 임박 알림(동시 실행): userId={}, inventoryItemId={}", userId, item.inventoryItemId());
			}
		}
		if (targetIds.isEmpty()) {
			return false;
		}

		String body = "소비기한이 임박한 재료가 " + targetIds.size() + "개 있어요";
		boolean completed = delivery.complete(userId, created, items.getFirst().pushEnabled(), "expiry", PUSH_TITLE, body);
		if (completed) {
			self.getObject().markSent(targetIds, LocalDateTime.now(clock));
		}
		return completed;
	}

	@Transactional
	public Notification createOne(Long userId, ExpiryPushCandidate item, LocalDateTime now) {
		Notification notification = Notification.expiry(userId, item.inventoryItemId(),
			PUSH_TITLE, item.ingredientName() + "의 소비기한이 임박했어요", now, now);
		return notificationRepository.saveAndFlush(notification);
	}

	@Transactional
	public void markSent(List<Long> notificationIds, LocalDateTime sentAt) {
		notificationRepository.findAllById(notificationIds).forEach(n -> n.markSent(sentAt));
	}
}
