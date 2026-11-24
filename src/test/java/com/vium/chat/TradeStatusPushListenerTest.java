package com.vium.chat;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.vium.chat.service.TradeStatusPushEvent;
import com.vium.chat.service.TradeStatusPushListener;
import com.vium.notification.service.TradePushService;
import com.vium.trade.service.TradeSystemEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class TradeStatusPushListenerTest {

	private final TradePushService tradePushService = mock(TradePushService.class);
	private final TradeStatusPushListener listener = new TradeStatusPushListener(tradePushService);

	@ParameterizedTest
	@EnumSource(TradeSystemEvent.class)
	void dispatchesEventMessageToRecipient(TradeSystemEvent event) {
		listener.onTradeStatusChanged(new TradeStatusPushEvent(10L, 20L, event));

		verify(tradePushService).sendTradeStatusPush(20L, 10L, event.message());
	}

	@Test
	void swallowsExceptionFromPushService() {
		doThrow(new RuntimeException("fcm down")).when(tradePushService)
			.sendTradeStatusPush(any(), any(), any());

		assertThatCode(() -> listener.onTradeStatusChanged(
			new TradeStatusPushEvent(1L, 2L, TradeSystemEvent.TRADE_REQUESTED))).doesNotThrowAnyException();
	}
}
