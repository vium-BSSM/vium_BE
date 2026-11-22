package com.vium.notification.service;

import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.notification.entity.UserPushToken;
import com.vium.notification.repository.UserPushTokenRepository;
import com.vium.user.service.UserSettingsService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 채팅 새 메시지 푸시 전용. notifications 테이블에는 적재하지 않는다(메시지마다 알림 목록 행이 쌓이는 것을 막기 위해).
 * ExpiryPushService와 "토큰 순회 전송 → 무효 토큰 삭제" 루프가 유사하지만, 승인된 범위에 따라
 * ExpiryPushService는 수정하지 않고 이 클래스에 독립적으로 둔다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatPushService {

	private static final String PUSH_TITLE = "새 메시지";
	private static final String PUSH_BODY = "새 메시지가 도착했어요";

	private final UserSettingsService userSettingsService;
	private final UserPushTokenRepository userPushTokenRepository;
	private final PushTokenService pushTokenService;
	private final FcmPushClient fcmPushClient;

	public void sendNewMessagePush(Long recipientUserId, Long chatRoomId) {
		boolean pushEnabled;
		try {
			pushEnabled = userSettingsService.getSettings(recipientUserId).pushEnabled();
		} catch (BusinessException e) {
			if (e.getErrorCode() != ErrorCode.UNAUTHORIZED) {
				throw e;
			}
			log.debug("탈퇴했거나 존재하지 않는 수신자라 채팅 푸시를 건너뜁니다: chatRoomId={}", chatRoomId);
			return;
		}
		if (!pushEnabled) {
			return;
		}

		List<UserPushToken> tokens = userPushTokenRepository.findAllByUserId(recipientUserId);
		if (tokens.isEmpty()) {
			return;
		}

		Map<String, String> data = Map.of("type", "chat", "chatRoomId", String.valueOf(chatRoomId));
		for (UserPushToken token : tokens) {
			PushResult result = fcmPushClient.send(token.getToken(), PUSH_TITLE, PUSH_BODY, data);
			if (result == PushResult.INVALID_TOKEN) {
				pushTokenService.deleteByToken(token.getToken());
			}
		}
	}
}
