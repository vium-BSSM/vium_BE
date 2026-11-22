package com.vium.notification.controller;

import com.vium.global.security.CurrentUserProvider;
import com.vium.notification.sse.SseConnectionRegistry;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 사용자 단위 단일 SSE 스트림. 기존 Bearer JWT 인증을 그대로 재사용하며 쿼리 파라미터 토큰은 허용하지
 * 않는다(SecurityConfig의 공통 보안 필터 체인을 그대로 탄다). 연결 관리(최대 연결 수, heartbeat, 최대
 * 수명)는 SseConnectionRegistry/SseHeartbeatScheduler가 담당한다.
 */
@RestController
@RequestMapping("/api/me/events")
@RequiredArgsConstructor
public class UserEventStreamController {

	private final SseConnectionRegistry registry;
	private final CurrentUserProvider currentUserProvider;

	@GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter stream(HttpServletResponse response) {
		Long userId = currentUserProvider.getCurrentUserId();
		response.setHeader("Cache-Control", "no-cache");
		response.setHeader("X-Accel-Buffering", "no");
		return registry.register(userId);
	}
}
