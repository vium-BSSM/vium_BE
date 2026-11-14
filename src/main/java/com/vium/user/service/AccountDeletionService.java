package com.vium.user.service;

import com.vium.auth.repository.UserSessionRepository;
import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.user.repository.UserCredentialRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AccountDeletionService {
	private final UserCredentialRepository userCredentialRepository;
	private final UserSessionRepository userSessionRepository;
	private final Clock authClock;

	@Transactional
	public void deleteAccount(Long userId) {
		var now = LocalDateTime.ofInstant(authClock.instant(), ZoneOffset.UTC);
		int updated = userCredentialRepository.softDelete(userId, now);
		if (updated == 0) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		userSessionRepository.deleteByUserId(userId);
	}
}
