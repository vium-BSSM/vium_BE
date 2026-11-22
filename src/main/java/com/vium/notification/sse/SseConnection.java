package com.vium.notification.sse;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * emitter.send()는 동시 호출에 안전하지 않아(Spring 문서 명시) 전송을 ReentrantLock으로 직렬화한다.
 * 락 획득 실패(2초) 또는 전송 중 IOException이면 연결을 종료해 등록된 onCompletion 콜백이 레지스트리에서
 * 이 연결을 제거하게 한다.
 */
@Slf4j
final class SseConnection {

	private static final long SEND_LOCK_TIMEOUT_SECONDS = 2;

	private final SseEmitter emitter;
	private final Instant connectedAt;
	// 패키지 전용 접근(테스트에서 다른 스레드가 보유하도록 만들어 락 타임아웃 분기를 검증하기 위함)
	final ReentrantLock sendLock = new ReentrantLock();

	SseConnection(SseEmitter emitter, Instant connectedAt) {
		this.emitter = emitter;
		this.connectedAt = connectedAt;
	}

	Instant connectedAt() {
		return connectedAt;
	}

	void send(String eventName, Object data) {
		boolean acquired;
		try {
			acquired = sendLock.tryLock(SEND_LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return;
		}
		if (!acquired) {
			log.warn("SSE 연결 전송 락 획득 실패로 연결을 종료합니다");
			complete();
			return;
		}
		try {
			emitter.send(SseEmitter.event().name(eventName).data(data));
		} catch (Exception e) {
			// IOException(클라이언트 연결 끊김) 외에 이미 완료된 emitter에 보내는 경쟁 상황의
			// IllegalStateException도 여기서 흡수한다 — 한 연결의 실패가 같은 사용자의 다른 연결로의
			// 순차 전송을 중단시키면 안 되기 때문이다.
			log.debug("SSE 연결 전송 실패로 연결을 종료합니다");
			completeWithError(e);
		} finally {
			sendLock.unlock();
		}
	}

	void complete() {
		emitter.complete();
	}

	void completeWithError(Throwable cause) {
		emitter.completeWithError(cause);
	}
}
