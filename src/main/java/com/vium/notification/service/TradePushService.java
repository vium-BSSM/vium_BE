package com.vium.notification.service;

import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.user.service.UserSettingsService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 거래 상태 변경 푸시 전용. notifications 테이블에는 적재하지 않는다.
 * trade 도메인 enum에 의존하지 않도록 body 문구는 호출자(리스너)가 미리 꺼내 전달한다.
 * 토큰 순회 전송·무효 토큰 삭제는 PushDispatchService에 위임한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TradePushService {

	private static final String PUSH_TITLE = "거래 알림";

	private final UserSettingsService userSettingsService;
	private final PushDispatchService pushDispatchService;

	public void sendTradeStatusPush(Long recipientUserId, Long chatRoomId, String body) {
		boolean pushEnabled;
		try {
			pushEnabled = userSettingsService.getSettings(recipientUserId).pushEnabled();
		} catch (BusinessException e) {
			if (e.getErrorCode() != ErrorCode.UNAUTHORIZED) {
				throw e;
			}
			log.debug("탈퇴했거나 존재하지 않는 수신자라 거래 상태 푸시를 건너뜁니다: chatRoomId={}", chatRoomId);
			return;
		}
		if (!pushEnabled) {
			return;
		}

		Map<String, String> data = Map.of("type", "trade", "chatRoomId", String.valueOf(chatRoomId));
		pushDispatchService.sendToUser(recipientUserId, PUSH_TITLE, body, data);
	}
}
