package com.vium.notification.service;

import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.inventory.service.InventoryService;
import com.vium.notification.dto.ConsumptionCheckKeepResponse;
import com.vium.notification.dto.ConsumptionCheckResponse;
import com.vium.notification.dto.ExpiryNotificationResponse;
import com.vium.notification.dto.NotificationDto;
import com.vium.notification.dto.NotificationListResponse;
import com.vium.notification.dto.NotificationReadResponse;
import com.vium.notification.entity.Notification;
import com.vium.notification.repository.NotificationRepository;
import com.vium.user.service.UserSettingsService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationService {

	private final NotificationRepository notifications;
	private final InventoryService inventoryService;
	private final UserSettingsService userSettingsService;
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

	/**
	 * 소비기한 임박 알림(팝업/위젯 공용). notifications 테이블에 행을 만들지 않고 조회 시점에 계산한다.
	 * 기준은 DashboardQueryRepository.countExpiringSoon과 동일: status=active and expiresOn <= 오늘+expiryAlertDays.
	 */
	@Transactional(readOnly = true)
	public ExpiryNotificationResponse expiringSoon(Long userId) {
		LocalDate today = LocalDate.now(clock);
		int expiryAlertDays = userSettingsService.getExpiryAlertDays(userId);
		LocalDate expiresThrough = today.plusDays(expiryAlertDays);
		var items = inventoryService.findExpiringIngredients(userId, expiresThrough).stream()
			.map(item -> new ExpiryNotificationResponse.Item(
				item.inventoryItemId(), item.name(), (int) ChronoUnit.DAYS.between(today, item.expiresOn())))
			.toList();
		return new ExpiryNotificationResponse(items);
	}

	@Transactional(readOnly = true)
	public ConsumptionCheckResponse consumptionCheck(Long userId) {
		var items = inventoryService.findConsumptionCheckCandidates(userId, LocalDate.now(clock)).stream()
			.map(item -> new ConsumptionCheckResponse.Item(
				item.inventoryItemId(), item.name(), item.remainingQuantity(), item.unit(), item.expiresOn()))
			.toList();
		return new ConsumptionCheckResponse(items);
	}

	@Transactional
	public ConsumptionCheckKeepResponse confirmKeep(Long userId, Long inventoryItemId) {
		LocalDate confirmedExpiresOn = inventoryService.confirmKeep(userId, inventoryItemId);
		markConsumeCheckRead(userId, inventoryItemId);
		return new ConsumptionCheckKeepResponse(inventoryItemId, confirmedExpiresOn);
	}

	/**
	 * 소진 확인 질문에 답한 것으로 본다. '아니오'(keep)와 '예'(dispose 도메인의 상태 변경) 양쪽에서 호출한다.
	 */
	@Transactional
	public void markConsumeCheckRead(Long userId, Long inventoryItemId) {
		notifications.findByUserIdAndInventoryItemIdAndType(userId, inventoryItemId, "consume_check")
			.ifPresent(notification -> notification.markRead(LocalDateTime.now(clock)));
	}

	private NotificationDto toDto(Notification notification) {
		return new NotificationDto(notification.getId(), notification.getType(), notification.getTitle(),
			notification.getBody(), notification.getScheduledAt(), notification.getSentAt(), notification.getReadAt());
	}
}
