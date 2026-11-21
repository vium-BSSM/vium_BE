package com.vium.notification.service;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class FirebaseFcmPushClient implements FcmPushClient {

	private final FirebaseMessaging firebaseMessaging;
	private final int timeoutSeconds;

	public FirebaseFcmPushClient(FirebaseMessaging firebaseMessaging, int timeoutSeconds) {
		this.firebaseMessaging = firebaseMessaging;
		this.timeoutSeconds = timeoutSeconds;
	}

	@Override
	public PushResult send(String token, String title, String body, Map<String, String> data) {
		Message message = Message.builder()
			.setToken(token)
			.setNotification(Notification.builder().setTitle(title).setBody(body).build())
			.putAllData(data)
			.build();
		try {
			firebaseMessaging.sendAsync(message).get(timeoutSeconds, TimeUnit.SECONDS);
			return PushResult.SUCCESS;
		} catch (ExecutionException e) {
			if (e.getCause() instanceof FirebaseMessagingException fme
					&& fme.getMessagingErrorCode() == MessagingErrorCode.UNREGISTERED) {
				return PushResult.INVALID_TOKEN;
			}
			log.warn("FCM 전송 실패", e.getCause());
			return PushResult.FAILED;
		} catch (TimeoutException e) {
			log.warn("FCM 전송 타임아웃: timeoutSeconds={}", timeoutSeconds);
			return PushResult.FAILED;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return PushResult.FAILED;
		}
	}
}
