package com.vium.auth.service;

import com.vium.auth.dto.LoginRequest;
import com.vium.auth.dto.LoginResponse;
import com.vium.auth.entity.UserSession;
import com.vium.auth.repository.UserSessionRepository;
import com.vium.user.service.UserLoginService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {
	private final UserLoginService userLoginService;
	private final TokenService tokenService;
	private final UserSessionRepository userSessionRepository;

	@Transactional
	public LoginResponse login(LoginRequest request) {
		var user = userLoginService.authenticate(request.email(), request.password());
		var tokens = tokenService.issue(user.id());
		userSessionRepository.save(UserSession.create(user.id(), tokens.refreshToken(),
			tokens.issuedAt(), tokens.refreshExpiresAt()));
		return new LoginResponse(tokens.accessToken(), tokens.refreshToken(), user);
	}
}
