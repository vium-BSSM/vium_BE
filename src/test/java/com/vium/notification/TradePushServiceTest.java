package com.vium.notification;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.notification.service.PushDispatchService;
import com.vium.notification.service.TradePushService;
import com.vium.user.dto.UserSettingsResponse;
import com.vium.user.service.UserSettingsService;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TradePushServiceTest {

	private final UserSettingsService userSettingsService = mock(UserSettingsService.class);
	private final PushDispatchService pushDispatchService = mock(PushDispatchService.class);
	private final TradePushService service = new TradePushService(userSettingsService, pushDispatchService);

	private UserSettingsResponse settings(boolean pushEnabled) {
		return new UserSettingsResponse(3, true, true, pushEnabled);
	}

	@Test
	void sendsPushWithTradeTitleAndMinimalData() {
		when(userSettingsService.getSettings(1L)).thenReturn(settings(true));

		service.sendTradeStatusPush(1L, 99L, "거래가 수락되었어요.");

		verify(pushDispatchService).sendToUser(eq(1L), eq("거래 알림"), eq("거래가 수락되었어요."),
			eq(Map.of("type", "trade", "chatRoomId", "99")));
	}

	@Test
	void skipsWhenPushDisabled() {
		when(userSettingsService.getSettings(1L)).thenReturn(settings(false));

		service.sendTradeStatusPush(1L, 99L, "거래가 수락되었어요.");

		verifyNoInteractions(pushDispatchService);
	}

	@Test
	void skipsSilentlyWhenRecipientWithdrawnOrMissing() {
		when(userSettingsService.getSettings(1L)).thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED));

		service.sendTradeStatusPush(1L, 99L, "거래가 수락되었어요.");

		verifyNoInteractions(pushDispatchService);
	}

	@Test
	void rethrowsNonUnauthorizedBusinessExceptions() {
		when(userSettingsService.getSettings(1L)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		assertThatThrownBy(() -> service.sendTradeStatusPush(1L, 99L, "거래가 수락되었어요."))
			.isInstanceOf(BusinessException.class)
			.extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
		verify(pushDispatchService, never()).sendToUser(eq(1L), org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
	}
}
