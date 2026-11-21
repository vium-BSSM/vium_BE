package com.vium.chat.service;

import com.vium.notification.service.ChatPushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 메시지 저장 트랜잭션이 커밋된 뒤에만(AFTER_COMMIT) 실행되며, 전용 풀(chatPushExecutor)에서
 * 비동기로 처리해 요청 스레드를 지연시키지 않는다. 예외는 여기서 전부 삼키고 chatRoomId만 로그로 남긴다
 * (메시지 내용·토큰·사용자 ID는 남기지 않는다).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChatMessagePushListener {

	private final ChatPushService chatPushService;

	@Async("chatPushExecutor")
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onChatMessageSent(ChatMessageSentEvent event) {
		try {
			chatPushService.sendNewMessagePush(event.recipientUserId(), event.chatRoomId());
		} catch (Exception e) {
			log.warn("채팅 메시지 푸시 처리 실패: chatRoomId={}", event.chatRoomId(), e);
		}
	}
}
