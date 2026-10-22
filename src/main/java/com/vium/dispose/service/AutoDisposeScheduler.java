package com.vium.dispose.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 자동 폐기 배치의 정기 실행을 담당한다.
 * disposal.auto-dispose.enabled=true일 때만 이 컴포넌트 자체가 빈으로 등록되므로,
 * false(기본값)면 {@code @Scheduled} 작업 자체가 Spring에 등록되지 않는다.
 * {@link AutoDisposeService}는 조건과 무관하게 항상 존재해야 하므로 분리했다.
 */
@Component
@ConditionalOnProperty(name = "disposal.auto-dispose.enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class AutoDisposeScheduler {

	private final AutoDisposeService autoDisposeService;

	@Scheduled(cron = "${disposal.auto-dispose.cron:0 10 0 * * *}", zone = "${disposal.auto-dispose.time-zone:Asia/Seoul}")
	public void run() {
		autoDisposeService.disposeExpiredItems();
	}
}
