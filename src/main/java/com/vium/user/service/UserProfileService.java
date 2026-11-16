package com.vium.user.service;

import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.user.dto.UserIdentity;
import com.vium.user.dto.UserProfileRow;
import com.vium.user.repository.UserCredentialRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserProfileService {
	// 회원가입(RegisterRequest)의 @Size(max = 80)과 같은 한도.
	private static final int DISPLAY_NAME_MAX_LENGTH = 80;

	private final UserCredentialRepository userCredentialRepository;
	private final Clock authClock;

	@Transactional(readOnly = true)
	public UserIdentity getProfile(Long userId) {
		return userCredentialRepository.findActiveIdentityById(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
	}

	@Transactional(readOnly = true)
	public UserProfileRow getProfileView(Long userId) {
		return userCredentialRepository.findActiveProfileById(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
	}

	/**
	 * 다른 도메인(예: credit)이 users 행을 잠그고 존재 여부를 확인할 때 쓴다.
	 * 호출자의 트랜잭션에 합류하며, 잠금은 그 트랜잭션이 끝날 때까지 유지된다.
	 */
	@Transactional
	public void lockActiveUser(Long userId) {
		if (!userCredentialRepository.lockActiveById(userId)) {
			throw new BusinessException(ErrorCode.USER_NOT_FOUND);
		}
	}

	/**
	 * 다른 도메인(예: safety)이 대상 사용자가 존재하고 탈퇴하지 않았는지만 확인할 때 쓴다. 잠그지 않는다.
	 */
	@Transactional(readOnly = true)
	public void requireActiveUser(Long userId) {
		if (!userCredentialRepository.existsActiveById(userId)) {
			throw new BusinessException(ErrorCode.USER_NOT_FOUND);
		}
	}

	@Transactional
	public UserProfileRow updateProfile(Long userId, String rawDisplayName, Long profileImageId) {
		String displayName = normalizeDisplayName(rawDisplayName);
		var now = LocalDateTime.ofInstant(authClock.instant(), ZoneOffset.UTC);
		int updated = userCredentialRepository.updateProfile(userId, displayName, profileImageId, now);
		if (updated == 0) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		return getProfileView(userId);
	}

	private String normalizeDisplayName(String rawDisplayName) {
		if (rawDisplayName == null) {
			return null;
		}
		String trimmed = rawDisplayName.strip();
		if (trimmed.isEmpty() || trimmed.length() > DISPLAY_NAME_MAX_LENGTH) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST,
				"닉네임은 공백을 제외하고 1자 이상 " + DISPLAY_NAME_MAX_LENGTH + "자 이하여야 합니다");
		}
		return trimmed;
	}
}
