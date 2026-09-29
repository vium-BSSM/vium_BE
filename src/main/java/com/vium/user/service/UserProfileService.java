package com.vium.user.service;

import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.user.dto.UserIdentity;
import com.vium.user.repository.UserCredentialRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserProfileService {
	private final UserCredentialRepository userCredentialRepository;

	@Transactional(readOnly = true)
	public UserIdentity getProfile(Long userId) {
		return userCredentialRepository.findActiveIdentityById(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
	}
}
