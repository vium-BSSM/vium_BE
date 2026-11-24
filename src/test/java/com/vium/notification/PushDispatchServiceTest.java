package com.vium.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vium.notification.entity.PushPlatform;
import com.vium.notification.entity.UserPushToken;
import com.vium.notification.repository.UserPushTokenRepository;
import com.vium.notification.service.FcmPushClient;
import com.vium.notification.service.PushDispatchService;
import com.vium.notification.service.PushResult;
import com.vium.notification.service.PushTokenService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PushDispatchServiceTest {

	private final UserPushTokenRepository userPushTokenRepository = mock(UserPushTokenRepository.class);
	private final PushTokenService pushTokenService = mock(PushTokenService.class);
	private final FcmPushClient fcmPushClient = mock(FcmPushClient.class);

	private final PushDispatchService service =
		new PushDispatchService(userPushTokenRepository, pushTokenService, fcmPushClient);

	private static final Long USER_ID = 1L;
	private static final Map<String, String> DATA = Map.of("type", "expiry");

	private UserPushToken token(String value) {
		return UserPushToken.register(USER_ID, value, PushPlatform.ANDROID, LocalDateTime.now());
	}

	@Test
	void returnsTrueAndSendsToEveryTokenWhenAllSucceed() {
		when(userPushTokenRepository.findAllByUserId(USER_ID)).thenReturn(List.of(token("t1"), token("t2")));
		when(fcmPushClient.send(anyString(), anyString(), anyString(), anyMap())).thenReturn(PushResult.SUCCESS);

		boolean result = service.sendToUser(USER_ID, "제목", "본문", DATA);

		assertThat(result).isTrue();
		verify(fcmPushClient).send(eq("t1"), eq("제목"), eq("본문"), eq(DATA));
		verify(fcmPushClient).send(eq("t2"), eq("제목"), eq("본문"), eq(DATA));
	}

	@Test
	void returnsTrueWhenOnlySomeTokensSucceed() {
		when(userPushTokenRepository.findAllByUserId(USER_ID)).thenReturn(List.of(token("t1"), token("t2")));
		when(fcmPushClient.send(eq("t1"), any(), any(), any())).thenReturn(PushResult.SUCCESS);
		when(fcmPushClient.send(eq("t2"), any(), any(), any())).thenReturn(PushResult.FAILED);

		boolean result = service.sendToUser(USER_ID, "제목", "본문", DATA);

		assertThat(result).isTrue();
	}

	@Test
	void returnsFalseWhenAllTokensFail() {
		when(userPushTokenRepository.findAllByUserId(USER_ID)).thenReturn(List.of(token("t1"), token("t2")));
		when(fcmPushClient.send(anyString(), anyString(), anyString(), anyMap())).thenReturn(PushResult.FAILED);

		boolean result = service.sendToUser(USER_ID, "제목", "본문", DATA);

		assertThat(result).isFalse();
	}

	@Test
	void deletesTokenViaPushTokenServiceWhenFcmReturnsInvalidToken() {
		when(userPushTokenRepository.findAllByUserId(USER_ID)).thenReturn(List.of(token("stale-token")));
		when(fcmPushClient.send(eq("stale-token"), any(), any(), any())).thenReturn(PushResult.INVALID_TOKEN);

		boolean result = service.sendToUser(USER_ID, "제목", "본문", DATA);

		assertThat(result).isFalse();
		verify(pushTokenService).deleteByToken("stale-token");
	}

	@Test
	void returnsFalseAndSendsNothingWhenUserHasNoToken() {
		when(userPushTokenRepository.findAllByUserId(USER_ID)).thenReturn(List.of());

		boolean result = service.sendToUser(USER_ID, "제목", "본문", DATA);

		assertThat(result).isFalse();
		verify(fcmPushClient, never()).send(any(), any(), any(), any());
	}
}
