package com.vium.notification.service;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/** fcm.enabled=false(기본)일 때 쓰는 구현체. 실제로 전송하지 않고 로그만 남긴다. */
@Slf4j
public class NoOpFcmPushClient implements FcmPushClient {

	@Override
	public PushResult send(String token, String title, String body, Map<String, String> data) {
		log.info("FCM 비활성화 상태라 푸시 전송을 건너뜁니다: title={}", title);
		return PushResult.SUCCESS;
	}
}
