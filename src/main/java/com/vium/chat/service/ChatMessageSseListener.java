package com.vium.chat.service;

import com.vium.notification.sse.SseConnectionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * ChatMessagePushListener(FCM)와 같은 원칙으로 AFTER_COMMIT에만 실행되지만, 전용 풀(chatSseExecutor)을
 * 써서 FCM 전송 지연이 SSE 전송을 지연시키지 않게 한다. 레지스트리 조회만 하고 DB는 조회하지 않는다.
 * 예외는 여기서 전부 삼키고 chatRoomId만 로그로 남긴다(메시지 내용·사용자 ID는 남기지 않는다).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChatMessageSseListener {

	private final SseConnectionRegistry registry;

	@Async("chatSseExecutor")
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onChatMessageSent(ChatMessageSseEvent event) {
		try {
			registry.broadcast(event.recipientUserId(), "chat-message",
				new ChatMessageSseData(event.chatRoomId(), event.messageId()));
		} catch (Exception e) {
			log.warn("채팅 메시지 SSE 브로드캐스트 실패: chatRoomId={}", event.chatRoomId(), e);
		}
	}

	private record ChatMessageSseData(Long chatRoomId, Long messageId) {
	}
}
