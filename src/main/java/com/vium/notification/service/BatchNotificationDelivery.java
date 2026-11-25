package com.vium.notification.service;

import com.vium.notification.sse.SseConnectionRegistry;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class BatchNotificationDelivery {

	private final PushDispatchService pushDispatchService;
	private final SseConnectionRegistry sseConnections;

	/** 호출자는 새 알림 행의 커밋이 끝난 뒤 이 메서드를 호출한다. */
	boolean complete(Long userId, boolean created, boolean pushEnabled, String type, String title, String body) {
		Map<String, String> data = Map.of("type", type);
		if (created) {
			try {
				sseConnections.broadcast(userId, "notification", data);
			} catch (Exception e) {
				log.warn("알림 SSE 전달 실패: userId={}, type={}", userId, type, e);
			}
		}
		return !pushEnabled || pushDispatchService.deliverToUser(userId, title, body, data) != PushDeliveryResult.FAILED;
	}
}
