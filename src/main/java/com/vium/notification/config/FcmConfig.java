package com.vium.notification.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.vium.notification.service.FcmPushClient;
import com.vium.notification.service.FirebaseFcmPushClient;
import com.vium.notification.service.NoOpFcmPushClient;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(FcmProperties.class)
public class FcmConfig {

	@Bean
	@ConditionalOnProperty(name = "fcm.enabled", havingValue = "true")
	public FcmPushClient firebaseFcmPushClient(FcmProperties properties) throws IOException {
		if (properties.credentialsJson() == null || properties.credentialsJson().isBlank()) {
			throw new IllegalStateException(
				"fcm.enabled=true 이지만 FCM_CREDENTIALS_JSON이 설정되지 않았습니다");
		}
		GoogleCredentials credentials = GoogleCredentials.fromStream(
			new ByteArrayInputStream(properties.credentialsJson().getBytes(StandardCharsets.UTF_8)));
		FirebaseOptions options = FirebaseOptions.builder().setCredentials(credentials).build();
		FirebaseApp app = FirebaseApp.getApps().isEmpty()
			? FirebaseApp.initializeApp(options)
			: FirebaseApp.getInstance();
		return new FirebaseFcmPushClient(FirebaseMessaging.getInstance(app), properties.timeoutSeconds());
	}

	@Bean
	@ConditionalOnProperty(name = "fcm.enabled", havingValue = "false", matchIfMissing = true)
	public FcmPushClient noOpFcmPushClient() {
		return new NoOpFcmPushClient();
	}
}
