package com.vium.global.auth;

import org.springframework.stereotype.Component;

/**
 * 인증 붙이기 전까지 고정 사용자 ID를 반환한다.
 * 인증 구현 시 이 메서드 내부만 JWT에서 추출하도록 교체한다.
 */
@Component
public class CurrentUserProvider {

	public Long getCurrentUserId() {
		return 1L;
	}
}
