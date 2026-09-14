package com.vium.dispose.application;

import com.vium.dispose.presentation.dto.UpdateInventoryItemStatusRequest;
import com.vium.dispose.presentation.dto.UpdateInventoryItemStatusResponse;
import com.vium.global.code.ItemStatusRepository;
import com.vium.global.common.InvalidRequestException;
import com.vium.global.common.NotFoundException;
import com.vium.inventory.entity.ConsumptionEvent;
import com.vium.inventory.entity.InventoryItem;
import com.vium.inventory.repository.ConsumptionEventRepository;
import com.vium.inventory.repository.InventoryItemRepository;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UpdateInventoryItemStatusUseCase {

	private final InventoryItemRepository inventoryItemRepository;
	private final ConsumptionEventRepository consumptionEventRepository;
	private final ItemStatusRepository itemStatusRepository;

	private static final String STATUS_CONSUMED = "consumed";
	private static final String STATUS_DISPOSED = "disposed";
	private static final Short EVENT_SOURCE_MANUAL_ID = 1; // manual
	private static final Short EVENT_SOURCE_AUTO_ID = 2; // auto_estimated

	@Transactional
	public UpdateInventoryItemStatusResponse execute(Long userId, Long inventoryItemId,
			UpdateInventoryItemStatusRequest request) {
		validate(request);

		InventoryItem item = inventoryItemRepository.findByIdAndUserId(inventoryItemId, userId)
			.orElseThrow(() -> new NotFoundException("Inventory item not found"));

		Short newStatusId = getStatusIdByCode(request.status());
		item.updateStatus(request.quantity(), newStatusId);

		BigDecimal eventAmount = STATUS_DISPOSED.equals(request.status()) ?
			BigDecimal.valueOf(request.wasteAmount() != null ? request.wasteAmount() : 0) : null;

		ConsumptionEvent event = ConsumptionEvent.create(inventoryItemId, userId, request.status(),
				EVENT_SOURCE_MANUAL_ID, request.quantity(), eventAmount);

		inventoryItemRepository.save(item);
		consumptionEventRepository.save(event);

		return new UpdateInventoryItemStatusResponse(inventoryItemId, request.status(),
				item.getRemainingQuantity(), request.wasteQuantity(), request.wasteAmount());
	}

	private void validate(UpdateInventoryItemStatusRequest request) {
		if (request.status() == null || (!STATUS_CONSUMED.equals(request.status()) &&
				!STATUS_DISPOSED.equals(request.status()))) {
			throw new InvalidRequestException("Status must be 'consumed' or 'disposed'");
		}

		if (request.quantity() == null || request.quantity().compareTo(BigDecimal.ZERO) <= 0) {
			throw new InvalidRequestException("Quantity must be greater than 0");
		}

		if (STATUS_DISPOSED.equals(request.status()) &&
				(request.wasteQuantity() == null || request.wasteQuantity().compareTo(BigDecimal.ZERO) < 0)) {
			throw new InvalidRequestException("wasteQuantity is required when status is 'disposed'");
		}
	}

	private Short getStatusIdByCode(String code) {
		return itemStatusRepository.findByCode(code)
			.map(status -> status.getId())
			.orElseThrow(() -> new NotFoundException("Status not found: " + code));
	}
}
