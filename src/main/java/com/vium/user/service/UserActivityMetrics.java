package com.vium.user.service;

import com.vium.user.dto.UserActivityCounts;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 사용자 수 집계와 gauge의 정기 갱신을 담당한다.
 * metrics.user-activity.enabled=true일 때만 빈과 스케줄 작업이 등록된다.
 * 기동 직후 거짓 0을 보내지 않도록 첫 집계 성공 후 gauge를 등록한다.
 * 조회 실패 시 이전 값을 유지하며, 첫 성공 전에는 시리즈가 없다.
 */
@Component
@ConditionalOnProperty(name = "metrics.user-activity.enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class UserActivityMetrics {
	private final UserActivityService userActivityService;
	private final MeterRegistry meterRegistry;

	private final AtomicLong activeToday = new AtomicLong();
	private final AtomicLong activeLast7Days = new AtomicLong();
	private final AtomicLong registered = new AtomicLong();
	private final AtomicLong signupsToday = new AtomicLong();
	private final AtomicBoolean gaugesRegistered = new AtomicBoolean();

	@PostConstruct
	void logActivation() {
		log.info("사용자 활동 메트릭 활성화: 갱신 간격 5분");
	}

	@Scheduled(initialDelay = 10, fixedDelay = 300, timeUnit = TimeUnit.SECONDS)
	public void refresh() {
		UserActivityCounts counts;
		try {
			counts = userActivityService.currentCounts();
		} catch (RuntimeException e) {
			log.warn("사용자 활동 메트릭 갱신 실패: 이전 값을 유지한다");
			throw e;
		}
		activeToday.set(counts.activeToday());
		activeLast7Days.set(counts.activeLast7Days());
		registered.set(counts.registered());
		signupsToday.set(counts.signupsToday());
		if (gaugesRegistered.compareAndSet(false, true)) {
			registerGauges();
		}
	}

	private void registerGauges() {
		Gauge.builder("vium.users.active", activeToday, AtomicLong::get).tag("period", "today")
			.description("Distinct users issued a session since 00:00 Asia/Seoul today").register(meterRegistry);
		Gauge.builder("vium.users.active", activeLast7Days, AtomicLong::get).tag("period", "7d")
			.description("Distinct users issued a session in the last 7 Asia/Seoul calendar days").register(meterRegistry);
		Gauge.builder("vium.users.registered", registered, AtomicLong::get)
			.description("Users not withdrawn").register(meterRegistry);
		Gauge.builder("vium.users.signups", signupsToday, AtomicLong::get).tag("period", "today")
			.description("Users registered since 00:00 Asia/Seoul today and not withdrawn").register(meterRegistry);
	}
}
