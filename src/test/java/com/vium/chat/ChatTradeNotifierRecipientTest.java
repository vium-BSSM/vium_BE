package com.vium.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vium.chat.entity.ChatMessage;
import com.vium.chat.entity.ChatParticipant;
import com.vium.chat.entity.ChatRoom;
import com.vium.chat.repository.ChatMessageRepository;
import com.vium.chat.repository.ChatParticipantRepository;
import com.vium.chat.repository.ChatRoomRepository;
import com.vium.chat.service.ChatTradeNotifier;
import com.vium.chat.service.TradeStatusPushEvent;
import com.vium.trade.entity.Trade;
import com.vium.trade.service.TradeSystemEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 수신자 판정(recipientsOf)만 확인한다 — actorId가 null이면 양쪽, 아니면 상대방에게만 push 이벤트가 발행돼야 한다.
 * 기존 SYSTEM 메시지·SSE 이벤트 동작은 TradeChatNotifierIntegrationTest가 이미 커버한다.
 */
class ChatTradeNotifierRecipientTest {

	private static final Long BUYER = 10L;
	private static final Long SELLER = 20L;
	private static final Long ROOM_ID = 555L;

	private final ChatRoomRepository chatRoomRepository = mock(ChatRoomRepository.class);
	private final ChatParticipantRepository chatParticipantRepository = mock(ChatParticipantRepository.class);
	private final ChatMessageRepository chatMessageRepository = mock(ChatMessageRepository.class);
	private final Clock clock = Clock.fixed(Instant.parse("2026-11-24T00:00:00Z"), ZoneOffset.UTC);
	private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

	private ChatTradeNotifier notifier;

	@BeforeEach
	void setUp() {
		notifier = new ChatTradeNotifier(chatRoomRepository, chatParticipantRepository, chatMessageRepository,
			clock, eventPublisher);

		ChatRoom room = mock(ChatRoom.class);
		when(room.getId()).thenReturn(ROOM_ID);
		when(chatRoomRepository.findByPostIdAndBuyerId(any(), any())).thenReturn(Optional.of(room));

		ChatMessage message = mock(ChatMessage.class);
		when(message.getId()).thenReturn(777L);
		when(chatMessageRepository.save(any())).thenReturn(message);

		when(chatParticipantRepository.findById(any())).thenReturn(Optional.<ChatParticipant>empty());
	}

	private List<TradeStatusPushEvent> capturePushEvents(Trade trade, TradeSystemEvent event, Long actorId) {
		notifier.notify(trade, event, actorId);

		ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
		verify(eventPublisher, org.mockito.Mockito.atLeastOnce()).publishEvent(captor.capture());
		return captor.getAllValues().stream()
			.filter(TradeStatusPushEvent.class::isInstance)
			.map(TradeStatusPushEvent.class::cast)
			.toList();
	}

	@Test
	void nullActorNotifiesBothParticipants() {
		Trade trade = Trade.request(1L, BUYER, SELLER, 0, java.time.LocalDateTime.now(clock));

		List<TradeStatusPushEvent> events = capturePushEvents(trade, TradeSystemEvent.TRADE_CANCELED, null);

		assertThat(events).extracting(TradeStatusPushEvent::recipientUserId)
			.containsExactlyInAnyOrder(BUYER, SELLER);
		assertThat(events).allSatisfy(e -> assertThat(e.chatRoomId()).isEqualTo(ROOM_ID));
	}

	@Test
	void sellerActorNotifiesBuyerOnly() {
		Trade trade = Trade.request(1L, BUYER, SELLER, 0, java.time.LocalDateTime.now(clock));

		List<TradeStatusPushEvent> events = capturePushEvents(trade, TradeSystemEvent.TRADE_ACCEPTED, SELLER);

		assertThat(events).extracting(TradeStatusPushEvent::recipientUserId).containsExactly(BUYER);
	}

	@Test
	void buyerActorNotifiesSellerOnly() {
		Trade trade = Trade.request(1L, BUYER, SELLER, 0, java.time.LocalDateTime.now(clock));

		List<TradeStatusPushEvent> events = capturePushEvents(trade, TradeSystemEvent.TRADE_REQUESTED, BUYER);

		assertThat(events).extracting(TradeStatusPushEvent::recipientUserId).containsExactly(SELLER);
	}
}
