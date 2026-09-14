package com.vium.global.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public class AuthenticationUtil {

	public static Long getCurrentUserId() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

		if (authentication == null || !authentication.isAuthenticated()) {
			// TODO: 인증 미들웨어가 완성될 때까지는 고정값 반환
			// 실제 구현 시 JWT에서 userId 추출
			return 1L;
		}

		// TODO: JWT 토큰에서 userId 추출 (principal의 필드명 확인 필요 - §5-2)
		// return Long.parseLong(authentication.getName());
		return 1L;
	}
}
