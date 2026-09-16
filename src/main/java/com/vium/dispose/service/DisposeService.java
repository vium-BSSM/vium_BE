package com.vium.dispose.service;

import com.vium.dispose.dto.GetConsumptionEventsResponse;
import com.vium.dispose.dto.UpdateInventoryItemStatusRequest;
import com.vium.dispose.dto.UpdateInventoryItemStatusResponse;
import com.vium.dispose.entity.ConsumptionEvent;
import com.vium.dispose.repository.ConsumptionEventQueryRepository;
import com.vium.dispose.repository.ConsumptionEventRepository;
import com.vium.global.code.ItemStatusRepository;
import com.vium.global.exception.InvalidRequestException;
import com.vium.global.exception.NotFoundException;
import com.vium.inventory.dto.InventoryState;
import com.vium.inventory.service.InventoryService;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DisposeService {

	private final InventoryService inventoryService;
	private final ConsumptionEventQueryRepository consumptionEventQueryRepository;
	private final ConsumptionEventRepository consumptionEventRepository;
	private final ItemStatusRepository itemStatusRepository;

	private static final String STATUS_CONSUMED = "consumed";
	private static final String STATUS_DISPOSED = "disposed";
	private static final Short EVENT_SOURCE_MANUAL_ID = 1;

	@Transactional
	public UpdateInventoryItemStatusResponse updateStatus(Long userId, Long inventoryItemId,
			UpdateInventoryItemStatusRequest request) {
		validate(request);

		Short newStatusId = getStatusIdByCode(request.status());
		InventoryState item = inventoryService.updateStatus(userId, inventoryItemId, request.quantity(), newStatusId);

		BigDecimal eventAmount = STATUS_DISPOSED.equals(request.status()) ?
			BigDecimal.valueOf(request.wasteAmount() != null ? request.wasteAmount() : 0) : null;

		ConsumptionEvent event = ConsumptionEvent.create(inventoryItemId, userId, request.status(),
				EVENT_SOURCE_MANUAL_ID, request.quantity(), eventAmount);

		consumptionEventRepository.save(event);

		return new UpdateInventoryItemStatusResponse(inventoryItemId, request.status(),
				item.remainingQuantity(), request.wasteQuantity(), request.wasteAmount());
	}

	@Transactional(readOnly = true)
	public GetConsumptionEventsResponse getEvents(Long userId, LocalDate from, LocalDate to, String eventType) {
		return new GetConsumptionEventsResponse(
			consumptionEventQueryRepository.findEvents(userId, from, to, eventType));
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
