package com.vium.notification.service;

import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.notification.dto.NotificationDto;
import com.vium.notification.dto.NotificationListResponse;
import com.vium.notification.dto.NotificationReadResponse;
import com.vium.notification.entity.Notification;
import com.vium.notification.repository.NotificationRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationService {

	private final NotificationRepository notifications;
	private final Clock clock;

	@Transactional(readOnly = true)
	public NotificationListResponse list(Long userId, Boolean isRead) {
		var found = isRead == null
			? notifications.findAllByUserIdOrderByScheduledAtDescIdDesc(userId)
			: isRead
				? notifications.findAllByUserIdAndReadAtIsNotNullOrderByScheduledAtDescIdDesc(userId)
				: notifications.findAllByUserIdAndReadAtIsNullOrderByScheduledAtDescIdDesc(userId);
		return new NotificationListResponse(found.stream().map(this::toDto).toList());
	}

	@Transactional
	public NotificationReadResponse markRead(Long userId, Long notificationId) {
		var notification = notifications.findByIdAndUserId(notificationId, userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		notification.markRead(LocalDateTime.now(clock));
		return new NotificationReadResponse(notification.getId(), notification.getReadAt());
	}

	/**
	 * 재료 삭제(post 도메인)가 해당 재고를 가리키는 알림을 함께 정리할 때 쓴다.
	 */
	@Transactional
	public void deleteByInventoryItemId(Long inventoryItemId) {
		notifications.deleteByInventoryItemId(inventoryItemId);
	}

	private NotificationDto toDto(Notification notification) {
		return new NotificationDto(notification.getId(), notification.getType(), notification.getTitle(),
			notification.getBody(), notification.getScheduledAt(), notification.getSentAt(), notification.getReadAt());
	}
}
