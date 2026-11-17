package com.vium.chat.service;

import com.vium.chat.entity.ChatMessage;
import com.vium.chat.entity.ChatParticipant;
import com.vium.chat.entity.ChatParticipantId;
import com.vium.chat.entity.ChatRoom;
import com.vium.chat.repository.ChatMessageRepository;
import com.vium.chat.repository.ChatParticipantRepository;
import com.vium.chat.repository.ChatRoomRepository;
import com.vium.trade.entity.Trade;
import com.vium.trade.service.TradeChatNotifier;
import com.vium.trade.service.TradeSystemEvent;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 거래 이벤트를 채팅방 SYSTEM 메시지로 남긴다. 거래 트랜잭션에 합류하므로(@Transactional 전파 없음)
 * 저장 중 예외가 발생하면 호출자(거래 서비스)의 트랜잭션이 그대로 롤백된다. 이 클래스 안에서는 예외를 삼키지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ChatTradeNotifier implements TradeChatNotifier {

	private static final Map<TradeSystemEvent, String> MESSAGES = Map.of(
		TradeSystemEvent.TRADE_REQUESTED, "거래 요청이 도착했어요.",
		TradeSystemEvent.TRADE_ACCEPTED, "거래가 수락되었어요.",
		TradeSystemEvent.TRADE_REJECTED, "거래가 거절되었어요.",
		TradeSystemEvent.TRADE_CANCELED, "거래가 취소되었어요.",
		TradeSystemEvent.TRADE_COMPLETED, "거래가 완료되었어요.",
		TradeSystemEvent.TRADE_AUTO_COMPLETE_SOON, "24시간 뒤 거래가 자동으로 완료돼요.");

	private final ChatRoomRepository chatRoomRepository;
	private final ChatParticipantRepository chatParticipantRepository;
	private final ChatMessageRepository chatMessageRepository;
	private final Clock clock;

	@Override
	public void notify(Trade trade, TradeSystemEvent event) {
		LocalDateTime now = LocalDateTime.now(clock);
		ChatRoom room = chatRoomRepository.findByPostIdAndBuyerId(trade.getPostId(), trade.getBuyerId())
			.orElseGet(() -> createRoom(trade, now));

		chatMessageRepository.save(ChatMessage.system(room.getId(), MESSAGES.get(event), event.name(), now));
		room.touchLastMessage(now);
		rejoin(room.getId(), trade.getBuyerId());
		rejoin(room.getId(), trade.getSellerId());
	}

	private ChatRoom createRoom(Trade trade, LocalDateTime now) {
		ChatRoom room = chatRoomRepository.save(ChatRoom.create(trade.getPostId(), trade.getBuyerId(),
			trade.getSellerId(), now));
		chatParticipantRepository.save(ChatParticipant.create(room.getId(), trade.getBuyerId()));
		chatParticipantRepository.save(ChatParticipant.create(room.getId(), trade.getSellerId()));
		return room;
	}

	private void rejoin(Long chatRoomId, Long userId) {
		chatParticipantRepository.findById(new ChatParticipantId(chatRoomId, userId)).ifPresent(ChatParticipant::rejoin);
	}
}
