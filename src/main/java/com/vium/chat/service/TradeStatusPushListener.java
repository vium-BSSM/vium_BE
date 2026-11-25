package com.vium.chat.service;

import com.vium.notification.service.TradePushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 거래 상태 변경 트랜잭션이 커밋된 뒤에만(AFTER_COMMIT) 실행되며, ChatMessagePushListener와 같은 전용 풀
 * (chatPushExecutor)에서 비동기로 처리한다. 거래 이벤트 빈도가 채팅 메시지보다 훨씬 낮아 별도 풀을 두지 않는다.
 * 예외는 여기서 전부 삼키고 chatRoomId만 로그로 남긴다(메시지 내용·토큰·사용자 ID는 남기지 않는다).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TradeStatusPushListener {

	private final TradePushService tradePushService;

	@Async("chatPushExecutor")
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onTradeStatusChanged(TradeStatusPushEvent event) {
		try {
			tradePushService.sendTradeStatusPush(event.recipientUserId(), event.chatRoomId(), event.event().message());
		} catch (Exception e) {
			log.warn("거래 상태 변경 푸시 처리 실패: chatRoomId={}", event.chatRoomId(), e);
		}
	}
}
