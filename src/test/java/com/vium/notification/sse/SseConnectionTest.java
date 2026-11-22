package com.vium.notification.sse;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SseConnection은 패키지 전용이라 같은 패키지의 단위 테스트로 직접 검증한다. 실제 서블릿 응답 없이
 * SseEmitter를 생성하면 handler가 아직 연결되지 않아 send()가 바이트를 쓰지 않고 조용히 큐잉될 뿐
 * 예외를 던지지 않고, complete()/completeWithError()도 handler가 없으면 onCompletion 콜백을 실행하지
 * 않는다(ResponseBodyEmitter.complete() 바이트코드 확인). 그래서 완료 여부는 onCompletion이 아니라
 * complete()/completeWithError() 호출 자체를 가로채는 테스트 전용 SseEmitter 서브클래스로 관찰한다.
 * 새 테스트 의존성 없이 순수 JDK 스레드만 사용한다.
 */
class SseConnectionTest {

	private static final class ObservableSseEmitter extends SseEmitter {
		private final AtomicBoolean completed = new AtomicBoolean(false);

		@Override
		public void complete() {
			completed.set(true);
			super.complete();
		}

		@Override
		public void completeWithError(Throwable ex) {
			completed.set(true);
			super.completeWithError(ex);
		}
	}

	@Test
	void sendSucceedsWithoutCompletingWhenLockIsFree() {
		ObservableSseEmitter emitter = new ObservableSseEmitter();
		SseConnection connection = new SseConnection(emitter, Instant.now());

		connection.send("ping", "ping");

		assertThat(emitter.completed.get()).isFalse();
	}

	@Test
	void sendCompletesConnectionWhenSendLockTimesOut() throws InterruptedException {
		ObservableSseEmitter emitter = new ObservableSseEmitter();
		SseConnection connection = new SseConnection(emitter, Instant.now());

		Thread holder = new Thread(() -> {
			connection.sendLock.lock();
			try {
				Thread.sleep(3000);
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			} finally {
				connection.sendLock.unlock();
			}
		});
		holder.start();
		awaitLockHeldByOtherThread(connection);

		connection.send("ping", "ping");

		assertThat(emitter.completed.get()).isTrue();
		holder.join();
	}

	@Test
	void otherConnectionIsUnaffectedWhenOneConnectionFailsToSend() {
		ObservableSseEmitter failing = new ObservableSseEmitter();
		failing.complete();
		SseConnection failingConnection = new SseConnection(failing, Instant.now());

		ObservableSseEmitter healthy = new ObservableSseEmitter();
		SseConnection healthyConnection = new SseConnection(healthy, Instant.now());

		failingConnection.send("chat-message", "x");
		healthyConnection.send("chat-message", "x");

		assertThat(healthy.completed.get()).isFalse();
	}

	private void awaitLockHeldByOtherThread(SseConnection connection) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 1000;
		while (!connection.sendLock.isLocked() && System.currentTimeMillis() < deadline) {
			Thread.sleep(10);
		}
		assertThat(connection.sendLock.isLocked()).isTrue();
	}
}
