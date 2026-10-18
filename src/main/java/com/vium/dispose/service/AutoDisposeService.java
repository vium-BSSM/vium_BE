package com.vium.dispose.service;

import com.vium.global.code.EventSourceRepository;
import com.vium.global.code.ItemStatusRepository;
import com.vium.global.exception.NotFoundException;
import com.vium.inventory.dto.AutoDisposeCandidate;
import com.vium.inventory.service.InventoryService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 자동 폐기 처리 로직. 스케줄러({@link AutoDisposeScheduler})와
 * 수동 트리거 엔드포인트(POST /api/ingredients/auto-dispose, 미구현)가 공유하는 서비스.
 * disposal.auto-dispose.enabled 값과 무관하게 항상 빈으로 존재해야 하므로
 * 활성화 여부에 대한 조건은 이 클래스가 아니라 {@link AutoDisposeScheduler}에 둔다.
 */
@Service
@RequiredArgsConstructor
public class AutoDisposeService {

	private final InventoryService inventoryService;
	private final EventSourceRepository eventSourceRepository;
	private final ItemStatusRepository itemStatusRepository;
	private final AutoDisposeItemProcessor itemProcessor;
	private final Clock clock;

	private static final String STATUS_ACTIVE_CODE = "active";
	private static final String STATUS_DISPOSED_CODE = "disposed";
	private static final String EVENT_SOURCE_AUTO_ESTIMATED = "auto_estimated";

	public int disposeExpiredItems() {
		LocalDate today = LocalDate.now(clock);

		Short activeStatusId = itemStatusRepository.findByCode(STATUS_ACTIVE_CODE)
			.orElseThrow(() -> new NotFoundException("Item status not found: " + STATUS_ACTIVE_CODE))
			.getId();

		Short disposedStatusId = itemStatusRepository.findByCode(STATUS_DISPOSED_CODE)
			.orElseThrow(() -> new NotFoundException("Item status not found: " + STATUS_DISPOSED_CODE))
			.getId();

		Short eventSourceId = eventSourceRepository.findByCode(EVENT_SOURCE_AUTO_ESTIMATED)
			.orElseThrow(() -> new NotFoundException("Event source not found: " + EVENT_SOURCE_AUTO_ESTIMATED))
			.getId();

		List<AutoDisposeCandidate> candidates = inventoryService
			.findAutoDisposeCandidates(activeStatusId, today);

		LocalDateTime now = LocalDateTime.now(clock);

		int processedCount = 0;
		for (AutoDisposeCandidate candidate : candidates) {
			boolean disposed = itemProcessor.disposeOneItem(
				candidate, activeStatusId, disposedStatusId, eventSourceId, now);
			if (disposed) {
				processedCount++;
			}
		}

		return processedCount;
	}
}
