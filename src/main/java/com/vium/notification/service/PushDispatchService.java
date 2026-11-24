package com.vium.notification.service;

import com.vium.notification.entity.UserPushToken;
import com.vium.notification.repository.UserPushTokenRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 사용자의 등록된 모든 토큰에 순회 전송한다. 무효 토큰(UNREGISTERED)은 PushTokenService를 통해 삭제한다.
 * ExpiryPushService와 ChatPushService가 공유하는 "토큰 조회 → 전송 → 무효 토큰 삭제" 로직이다.
 */
@Service
@RequiredArgsConstructor
public class PushDispatchService {

	private final UserPushTokenRepository userPushTokenRepository;
	private final PushTokenService pushTokenService;
	private final FcmPushClient fcmPushClient;

	public boolean sendToUser(Long userId, String title, String body, Map<String, String> data) {
		boolean anySuccess = false;
		for (UserPushToken token : userPushTokenRepository.findAllByUserId(userId)) {
			PushResult result = fcmPushClient.send(token.getToken(), title, body, data);
			if (result == PushResult.SUCCESS) {
				anySuccess = true;
			} else if (result == PushResult.INVALID_TOKEN) {
				pushTokenService.deleteByToken(token.getToken());
			}
		}
		return anySuccess;
	}
}
