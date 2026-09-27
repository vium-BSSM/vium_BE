package com.vium.auth.service;

import com.vium.auth.dto.LoginRequest;
import com.vium.auth.dto.LoginResponse;
import com.vium.auth.dto.RefreshRequest;
import com.vium.auth.dto.RefreshResponse;
import com.vium.auth.dto.RegisterRequest;
import com.vium.auth.dto.RegisterResponse;
import com.vium.auth.entity.UserSession;
import com.vium.auth.repository.UserSessionRepository;
import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.global.security.JwtProperties;
import com.vium.user.service.UserLoginService;
import com.vium.user.service.UserRegistrationService;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {
	private static final String INVALID_REFRESH_TOKEN = "리프레시 토큰이 유효하지 않습니다";
	private final UserLoginService userLoginService;
	private final UserRegistrationService userRegistrationService;
	private final TokenService tokenService;
	private final UserSessionRepository userSessionRepository;
	private final Clock authClock;
	private final JwtProperties jwtProperties;

	@Transactional
	public RefreshResponse refresh(RefreshRequest request) {
		// Lock before checking state so concurrent requests cannot rotate the same token twice.
		var session = userSessionRepository.findByRefreshTokenHash(UserSession.hashToken(request.refreshToken()))
			.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, INVALID_REFRESH_TOKEN));
		var now = authClock.instant();
		if (session.getRevokedAt() != null || session.isExpired(now)
				|| !userLoginService.isActiveUser(session.getUserId())) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED, INVALID_REFRESH_TOKEN);
		}
		var tokens = tokenService.issue(session.getUserId());
		session.revoke(now);
		userSessionRepository.save(UserSession.create(session.getUserId(), tokens.refreshToken(),
			tokens.issuedAt(), tokens.refreshExpiresAt()));
		return new RefreshResponse(tokens.accessToken(), tokens.refreshToken(), jwtProperties.accessTokenSeconds());
	}

	@Transactional
	public RegisterResponse register(RegisterRequest request) {
		var user = userRegistrationService.register(request.email(), request.password(), request.displayName());
		return new RegisterResponse(user.id(), user.email(), user.displayName());
	}

	@Transactional
	public LoginResponse login(LoginRequest request) {
		var user = userLoginService.authenticate(request.email(), request.password());
		var tokens = tokenService.issue(user.id());
		userSessionRepository.save(UserSession.create(user.id(), tokens.refreshToken(),
			tokens.issuedAt(), tokens.refreshExpiresAt()));
		return new LoginResponse(tokens.accessToken(), tokens.refreshToken(), user);
	}
}
