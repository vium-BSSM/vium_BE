package com.vium.user.service;

import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.user.dto.UserIdentity;
import com.vium.user.repository.UserCredentialRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserRegistrationService {
	private final UserCredentialRepository userCredentialRepository;
	private final PasswordEncoder passwordEncoder;
	private final Clock authClock;

	@Transactional
	public UserIdentity register(String email, String password, String displayName) {
		if (password.codePointCount(0, password.length()) < 8) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, "비밀번호는 8자 이상이어야 합니다");
		}
		if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, "비밀번호는 UTF-8 기준 72바이트 이하여야 합니다");
		}
		try {
			return userCredentialRepository.create(email, passwordEncoder.encode(password), displayName,
				LocalDateTime.ofInstant(authClock.instant(), ZoneOffset.UTC));
		} catch (DuplicateKeyException e) {
			throw new BusinessException(ErrorCode.CONFLICT, "이미 사용 중인 이메일입니다");
		}
	}
}
