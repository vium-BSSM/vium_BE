package com.vium.dispose.service;

import com.vium.dispose.entity.ConsumptionEvent;
import com.vium.dispose.repository.ConsumptionEventRepository;
import com.vium.inventory.dto.AutoDisposeCandidate;
import com.vium.inventory.service.InventoryService;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 자동 폐기 대상 1건을 독립 트랜잭션으로 처리한다.
 * 재료 한 건의 실패가 배치 전체를 롤백하지 않도록 REQUIRES_NEW로 격리한다.
 */
@Component
@RequiredArgsConstructor
public class AutoDisposeItemProcessor {

	private static final String EVENT_TYPE_DISPOSED = "disposed";

	private final InventoryService inventoryService;
	private final ConsumptionEventRepository consumptionEventRepository;

	/**
	 * @return 실제로 폐기 처리했으면 true, 경쟁 상황으로 이미 변경되어 스킵했으면 false
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean disposeOneItem(AutoDisposeCandidate candidate, Short activeStatusId, Short disposedStatusId,
			Short eventSourceId, LocalDateTime now) {
		boolean updated = inventoryService.disposeIfUnchanged(
			candidate.inventoryItemId(), activeStatusId, disposedStatusId,
			candidate.remainingQuantity(), now);

		if (!updated) {
			return false;
		}

		var amount = ProportionalAmountCalculator.calculate(
			candidate.amount(), candidate.remainingQuantity(), candidate.initialQuantity());

		LocalDateTime occurredAt = candidate.expiresOn().plusDays(1).atStartOfDay();

		ConsumptionEvent event = ConsumptionEvent.create(
			candidate.inventoryItemId(), candidate.userId(), EVENT_TYPE_DISPOSED,
			eventSourceId, candidate.remainingQuantity(), amount, occurredAt);
		consumptionEventRepository.save(event);

		return true;
	}
}
