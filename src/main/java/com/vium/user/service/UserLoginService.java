package com.vium.user.service;

import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.user.dto.UserIdentity;
import com.vium.user.repository.UserCredentialRepository;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserLoginService {
	private final UserCredentialRepository userCredentialRepository;
	private final PasswordEncoder passwordEncoder;
	private final String dummyPasswordHash;

	public UserLoginService(UserCredentialRepository userCredentialRepository, PasswordEncoder passwordEncoder) {
		this.userCredentialRepository = userCredentialRepository;
		this.passwordEncoder = passwordEncoder;
		this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
	}

	@Transactional(readOnly = true)
	public UserIdentity authenticate(String email, String password) {
		if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, "비밀번호는 UTF-8 기준 72바이트 이하여야 합니다");
		}
		var user = userCredentialRepository.findActiveByEmail(email).orElse(null);
		String hash = user != null && user.passwordHash() != null ? user.passwordHash() : dummyPasswordHash;
		boolean matches = passwordEncoder.matches(password, hash);
		if (user == null || user.passwordHash() == null || !matches) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다");
		}
		return new UserIdentity(user.id(), user.email(), user.displayName());
	}
}
