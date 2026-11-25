package com.vium.notification.controller;

import com.vium.global.common.ApiResponse;
import com.vium.global.security.CurrentUserProvider;
import com.vium.notification.dto.ConsumptionCheckKeepResponse;
import com.vium.notification.dto.ConsumptionCheckResponse;
import com.vium.notification.dto.ExpiryNotificationResponse;
import com.vium.notification.dto.NotificationListResponse;
import com.vium.notification.dto.NotificationReadResponse;
import com.vium.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me/notifications")
@RequiredArgsConstructor
public class NotificationController {

	private final NotificationService notifications;
	private final CurrentUserProvider currentUser;

	@GetMapping
	public ApiResponse<NotificationListResponse> list(@RequestParam(required = false) Boolean isRead) {
		return ApiResponse.ok(notifications.list(currentUser.getCurrentUserId(), isRead));
	}

	@PatchMapping("/{notificationId}/read")
	public ApiResponse<NotificationReadResponse> markRead(@PathVariable Long notificationId) {
		return ApiResponse.ok(notifications.markRead(currentUser.getCurrentUserId(), notificationId));
	}

	@GetMapping("/expiry-popup")
	public ApiResponse<ExpiryNotificationResponse> expiryPopup() {
		return ApiResponse.ok(notifications.expiringSoon(currentUser.getCurrentUserId()));
	}

	@GetMapping("/expiry-widget")
	public ApiResponse<ExpiryNotificationResponse> expiryWidget() {
		return ApiResponse.ok(notifications.expiringSoon(currentUser.getCurrentUserId()));
	}

	@GetMapping("/consumption-check")
	public ApiResponse<ConsumptionCheckResponse> consumptionCheck() {
		return ApiResponse.ok(notifications.consumptionCheck(currentUser.getCurrentUserId()));
	}

	@PostMapping("/consumption-check/{ingredientId}/keep")
	public ApiResponse<ConsumptionCheckKeepResponse> confirmKeep(@PathVariable Long ingredientId) {
		return ApiResponse.ok(notifications.confirmKeep(currentUser.getCurrentUserId(), ingredientId));
	}
}
