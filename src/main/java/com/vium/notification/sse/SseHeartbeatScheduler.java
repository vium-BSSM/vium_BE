package com.vium.notification.sse;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * heartbeat와 최대 연결 수명 체크를 전용 단일 스레드에서 처리한다. 기존 배치 스케줄러들이 공유하는
 * spring.task.scheduling.pool.size=1 TaskScheduler(taskScheduler 빈)와는 완전히 분리한다 — 연결이
 * 많아지면 heartbeat 순회가 길어질 수 있는데, 같은 풀을 쓰면 TradeAutoComplete(10분 주기) 등 다른
 * 배치 트리거가 지연될 수 있기 때문이다.
 *
 * 이 executor는 의도적으로 Spring 빈으로 등록하지 않는다. Spring Boot의 taskScheduler 자동 구성은
 * TaskScheduler 또는 ScheduledExecutorService 타입 빈이 하나라도 있으면 전체를 생성하지 않도록
 * @ConditionalOnMissingBean이 걸려 있어(TaskSchedulingConfigurations$TaskSchedulerConfiguration),
 * 빈으로 등록하면 기존 모든 @Scheduled 배치가 돌던 taskScheduler 빈 자체가 사라진다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SseHeartbeatScheduler {

	private final SseConnectionRegistry registry;
	private final SseProperties properties;
	private final ScheduledExecutorService scheduler =
		Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "sse-heartbeat"));

	@PostConstruct
	void start() {
		long interval = properties.heartbeatIntervalSeconds();
		scheduler.scheduleWithFixedDelay(this::tickSafely, interval, interval, TimeUnit.SECONDS);
	}

	private void tickSafely() {
		try {
			registry.tick();
		} catch (Exception e) {
			log.warn("SSE heartbeat 틱 처리 중 오류가 발생했습니다", e);
		}
	}

	@PreDestroy
	void stop() {
		scheduler.shutdown();
		try {
			if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
				scheduler.shutdownNow();
			}
		} catch (InterruptedException e) {
			scheduler.shutdownNow();
			Thread.currentThread().interrupt();
		}
		registry.shutdown();
	}
}
