package com.vium.notification.service;

import com.vium.notification.dto.ConsumeCheckPushCandidate;
import com.vium.notification.entity.Notification;
import com.vium.notification.repository.ConsumeCheckPushQueryRepository;
import com.vium.notification.repository.NotificationRepository;
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
 * 재고별 독립 트랜잭션에서 알림을 만들고, 실패 이력이 있으면 같은 행을 재사용한다.
 * 새 행을 SSE로 알리고 FCM 전송이 성공하거나 생략되면 sent_at을 기록한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConsumeCheckPushService {

	private static final String PUSH_TITLE = "소진 확인";

	private final ConsumeCheckPushQueryRepository consumeCheckPushQueryRepository;
	private final NotificationRepository notificationRepository;
	private final BatchNotificationDelivery delivery;
	private final Clock clock;
	private final ObjectProvider<ConsumeCheckPushService> self;

	public int sendDailyConsumeCheckPush() {
		LocalDate today = LocalDate.now(clock);
		Map<Long, List<ConsumeCheckPushCandidate>> byUser = consumeCheckPushQueryRepository
			.findCandidates(today).stream()
			.collect(Collectors.groupingBy(ConsumeCheckPushCandidate::userId, LinkedHashMap::new, Collectors.toList()));

		int processedUserCount = 0;
		for (var entry : byUser.entrySet()) {
			Long userId = entry.getKey();
			try {
				if (processUser(userId, entry.getValue())) {
					processedUserCount++;
				}
			} catch (Exception e) {
				log.error("소진 확인 푸시 처리 실패: userId={}", userId, e);
			}
		}
		return processedUserCount;
	}

	private boolean processUser(Long userId, List<ConsumeCheckPushCandidate> items) {
		LocalDateTime now = LocalDateTime.now(clock);
		List<Long> targetIds = new ArrayList<>();
		boolean created = false;
		String ingredientName = null;

		for (ConsumeCheckPushCandidate item : items) {
			Long notificationId = item.existingNotificationId();
			if (notificationId == null) {
				try {
					notificationId = self.getObject().createOne(userId, item, now).getId();
					created = true;
				} catch (DataIntegrityViolationException e) {
					log.info("이미 처리된 소진 확인 알림(동시 실행): userId={}, inventoryItemId={}",
						userId, item.inventoryItemId());
					continue;
				}
			}
			targetIds.add(notificationId);
			ingredientName = item.ingredientName();
		}
		if (targetIds.isEmpty()) {
			return false;
		}

		String body = targetIds.size() == 1
			? itemBody(ingredientName)
			: "오늘까지인 재료 " + targetIds.size() + "개, 다 드셨나요?";
		boolean completed = delivery.complete(userId, created, items.getFirst().pushEnabled(),
			"consume_check", PUSH_TITLE, body);
		if (completed) {
			self.getObject().markSent(targetIds, LocalDateTime.now(clock));
		}
		return completed;
	}

	@Transactional
	public Notification createOne(Long userId, ConsumeCheckPushCandidate item, LocalDateTime now) {
		Notification notification = Notification.consumeCheck(userId, item.inventoryItemId(),
			PUSH_TITLE, itemBody(item.ingredientName()), now, now);
		return notificationRepository.saveAndFlush(notification);
	}

	@Transactional
	public void markSent(List<Long> notificationIds, LocalDateTime sentAt) {
		notificationRepository.findAllById(notificationIds).forEach(notification -> notification.markSent(sentAt));
	}

	private static String itemBody(String ingredientName) {
		return ingredientName + ", 다 드셨어요?";
	}
}
