package com.vium.notification.service;

import java.util.Map;

public interface FcmPushClient {

	/**
	 * 예외를 던지지 않고 결과로만 알린다. data에는 개인정보(재료명·이메일·사용자 ID 등)를 넣지 않는다.
	 */
	PushResult send(String token, String title, String body, Map<String, String> data);
}
