package com.vium.user.service;

import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.user.dto.UserSettingsResponse;
import com.vium.user.dto.UserSettingsUpdateRequest;
import com.vium.user.repository.UserSettingsRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserSettingsService {

	private static final int EXPIRY_ALERT_DAYS_MIN = 1;
	private static final int EXPIRY_ALERT_DAYS_MAX = 14;

	private final UserSettingsRepository userSettingsRepository;
	private final Clock authClock;

	@Transactional(readOnly = true)
	public int getExpiryAlertDays(Long userId) {
		return userSettingsRepository.findExpiryAlertDays(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "사용자를 찾을 수 없습니다"));
	}

	@Transactional(readOnly = true)
	public UserSettingsResponse getSettings(Long userId) {
		return userSettingsRepository.findActiveSettingsById(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
	}

	@Transactional
	public UserSettingsResponse updateSettings(Long userId, UserSettingsUpdateRequest request) {
		if (request.expiryAlertDays() == null && request.autoDisposeEnabled() == null
				&& request.shoppingReminderEnabled() == null && request.pushEnabled() == null) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, "수정할 필드가 없습니다");
		}
		if (request.expiryAlertDays() != null
				&& (request.expiryAlertDays() < EXPIRY_ALERT_DAYS_MIN || request.expiryAlertDays() > EXPIRY_ALERT_DAYS_MAX)) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST,
				"expiryAlertDays는 " + EXPIRY_ALERT_DAYS_MIN + "~" + EXPIRY_ALERT_DAYS_MAX + " 범위여야 합니다");
		}
		var now = LocalDateTime.ofInstant(authClock.instant(), ZoneOffset.UTC);
		int updated = userSettingsRepository.updateSettings(userId, request.expiryAlertDays(),
			request.autoDisposeEnabled(), request.shoppingReminderEnabled(), request.pushEnabled(), now);
		if (updated == 0) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		return getSettings(userId);
	}
}
