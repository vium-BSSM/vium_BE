package com.vium.notification.sse;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 사용자 단위 SSE 연결을 인메모리로만 관리한다(단일 인스턴스 전제, DB 조회 없음).
 * 사용자당 최대 연결 수를 넘으면 가장 오래된 연결을 종료하고, 종료된 연결은 onCompletion/onTimeout/onError
 * 콜백에서 이 레지스트리로부터 스스로를 제거한다(중복 제거는 Deque#remove가 멱등이라 안전하다).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SseConnectionRegistry {

	private final SseProperties properties;
	private final Clock clock;
	private final Map<Long, UserConnections> byUser = new ConcurrentHashMap<>();

	public SseEmitter register(Long userId) {
		long timeoutMillis = (properties.maxConnectionAgeSeconds() + 60L) * 1000L;
		SseEmitter emitter = new SseEmitter(timeoutMillis);
		SseConnection connection = new SseConnection(emitter, Instant.now(clock));
		UserConnections connections = byUser.computeIfAbsent(userId, id -> new UserConnections());

		emitter.onCompletion(() -> connections.remove(connection));
		emitter.onTimeout(connection::complete);
		emitter.onError(e -> connections.remove(connection));

		connections.add(connection, properties.maxConnectionsPerUser());
		connection.send("ping", "ping");
		return emitter;
	}

	public void broadcast(Long userId, String eventName, Object data) {
		UserConnections connections = byUser.get(userId);
		if (connections == null) {
			return;
		}
		connections.snapshot().forEach(connection -> connection.send(eventName, data));
	}

	/**
	 * heartbeat 틱 하나가 전송과 최대 수명 체크를 함께 처리한다(별도 틱을 만들지 않음).
	 */
	void tick() {
		Instant cutoff = Instant.now(clock).minusSeconds(properties.maxConnectionAgeSeconds());
		for (UserConnections connections : byUser.values()) {
			for (SseConnection connection : connections.snapshot()) {
				if (connection.connectedAt().isBefore(cutoff)) {
					connection.complete();
				} else {
					connection.send("ping", "ping");
				}
			}
		}
	}

	/**
	 * 테스트 전용 조회. handler가 없는 순수 단위 테스트에서는 ResponseBodyEmitter.complete()가
	 * onCompletion 콜백을 실행하지 않아(핸들러 null) 종료 여부를 콜백으로 관찰할 수 없다 — 대신 구조적으로
	 * 사용자당 연결 수를 직접 확인하기 위한 것이다.
	 */
	int sizeForTest(Long userId) {
		UserConnections connections = byUser.get(userId);
		return connections == null ? 0 : connections.snapshot().size();
	}

	/** 애플리케이션 종료 시 SseHeartbeatScheduler가 호출한다. 테스트에서도 연결 정리에 쓸 수 있다. */
	public void shutdown() {
		for (UserConnections connections : byUser.values()) {
			connections.snapshot().forEach(SseConnection::complete);
		}
	}

	private static final class UserConnections {
		private final Deque<SseConnection> connections = new ArrayDeque<>();
		private final ReentrantLock lock = new ReentrantLock();

		void add(SseConnection connection, int maxConnections) {
			SseConnection evicted = null;
			lock.lock();
			try {
				if (connections.size() >= maxConnections) {
					evicted = connections.pollFirst();
				}
				connections.addLast(connection);
			} finally {
				lock.unlock();
			}
			if (evicted != null) {
				evicted.complete();
			}
		}

		void remove(SseConnection connection) {
			lock.lock();
			try {
				connections.remove(connection);
			} finally {
				lock.unlock();
			}
		}

		List<SseConnection> snapshot() {
			lock.lock();
			try {
				return new ArrayList<>(connections);
			} finally {
				lock.unlock();
			}
		}
	}
}
