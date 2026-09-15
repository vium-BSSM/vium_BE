package com.vium.dispose.application;

import com.vium.inventory.entity.ConsumptionEvent;
import com.vium.inventory.entity.InventoryItem;
import com.vium.inventory.repository.ConsumptionEventRepository;
import com.vium.inventory.repository.InventoryItemRepository;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AutoDisposeInventoryItemsUseCase {

	private final InventoryItemRepository inventoryItemRepository;
	private final ConsumptionEventRepository consumptionEventRepository;

	private static final Short STATUS_DISPOSED_ID = 3;
	private static final Short EVENT_SOURCE_AUTO_ID = 2;

	@Transactional
	public int execute() {
		LocalDate yesterday = LocalDate.now().minusDays(1);

		List<InventoryItem> expiredItems = inventoryItemRepository
			.findAllByStatusIdNotAndExpiresOnBefore(STATUS_DISPOSED_ID, yesterday);

		int processedCount = 0;
		for (InventoryItem item : expiredItems) {
			item.updateStatus(item.getRemainingQuantity(), STATUS_DISPOSED_ID);

			ConsumptionEvent event = ConsumptionEvent.create(item.getId(), item.getUserId(), "disposed",
				EVENT_SOURCE_AUTO_ID, item.getRemainingQuantity(), null);

			inventoryItemRepository.save(item);
			consumptionEventRepository.save(event);
			processedCount++;
		}

		return processedCount;
	}
}
