package com.vium.dispose.service;

import com.vium.dispose.entity.ConsumptionEvent;
import com.vium.dispose.repository.ConsumptionEventRepository;
import com.vium.inventory.dto.InventoryState;
import com.vium.inventory.service.InventoryService;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AutoDisposeService {

	private final InventoryService inventoryService;
	private final ConsumptionEventRepository consumptionEventRepository;

	private static final Short STATUS_DISPOSED_ID = 3;
	private static final Short EVENT_SOURCE_AUTO_ID = 2;

	@Transactional
	public int disposeExpiredItems() {
		LocalDate yesterday = LocalDate.now().minusDays(1);

		List<InventoryState> expiredItems = inventoryService
			.findAutoDisposeCandidates(STATUS_DISPOSED_ID, yesterday);

		int processedCount = 0;
		for (InventoryState candidate : expiredItems) {
			InventoryState item = inventoryService.updateStatus(candidate.userId(), candidate.inventoryItemId(),
				candidate.remainingQuantity(), STATUS_DISPOSED_ID);

			ConsumptionEvent event = ConsumptionEvent.create(item.inventoryItemId(), item.userId(), "disposed",
				EVENT_SOURCE_AUTO_ID, item.remainingQuantity(), null);

			consumptionEventRepository.save(event);
			processedCount++;
		}

		return processedCount;
	}
}
