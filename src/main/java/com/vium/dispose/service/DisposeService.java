package com.vium.dispose.service;

import com.vium.dispose.dto.GetConsumptionEventsResponse;
import com.vium.dispose.dto.UpdateInventoryItemStatusRequest;
import com.vium.dispose.dto.UpdateInventoryItemStatusResponse;
import com.vium.dispose.entity.ConsumptionEvent;
import com.vium.dispose.repository.ConsumptionEventQueryRepository;
import com.vium.dispose.repository.ConsumptionEventRepository;
import com.vium.global.code.EventSourceRepository;
import com.vium.global.code.ItemStatusRepository;
import com.vium.global.exception.InvalidRequestException;
import com.vium.global.exception.NotFoundException;
import com.vium.inventory.dto.InventoryItemSnapshot;
import com.vium.inventory.dto.InventoryState;
import com.vium.inventory.service.InventoryService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
	private final EventSourceRepository eventSourceRepository;
	private final Clock clock;

	private static final String STATUS_CONSUMED = "consumed";
	private static final String STATUS_DISPOSED = "disposed";
	private static final String EVENT_SOURCE_MANUAL = "manual";

	@Transactional
	public UpdateInventoryItemStatusResponse updateStatus(Long userId, Long inventoryItemId,
			UpdateInventoryItemStatusRequest request) {
		// 검증 전에 행 락을 먼저 잡아야 동시 요청이 같은 남은 수량을 보고 둘 다 통과하지 않는다
		InventoryItemSnapshot snapshot = inventoryService.getSnapshotForUpdate(userId, inventoryItemId);

		validate(request, snapshot);

		// disposed 요청에서 wasteQuantity가 정확히 0이면 전체를 consumed로 처리한다 (유일한 예외 케이스)
		boolean zeroWasteDisposed = STATUS_DISPOSED.equals(request.status())
			&& request.wasteQuantity() != null
			&& request.wasteQuantity().compareTo(BigDecimal.ZERO) == 0;

		String effectiveStatus = zeroWasteDisposed ? STATUS_CONSUMED : request.status();

		// 폐기량 결정: disposed면 wasteQuantity(없으면 quantity 전체), 그 외(consumed/zeroWaste)는 quantity 전체
		BigDecimal wasteQty = STATUS_DISPOSED.equals(effectiveStatus)
			? (request.wasteQuantity() != null ? request.wasteQuantity() : request.quantity())
			: request.quantity();

		// 상태 변경 및 재고 업데이트 (같은 트랜잭션)
		// 정리 플로우 계약: quantity(전체 처리량)만큼 재고에서 차감한다. wasteQty는 폐기분/소진분을
		// 나누는 이벤트 기록 기준일 뿐, 재고 차감량은 항상 request.quantity() 전체다.
		Short newStatusId = getStatusIdByCode(effectiveStatus);
		InventoryState updatedItem = inventoryService.updateStatus(userId, inventoryItemId, request.quantity(), newStatusId);

		Short eventSourceId = eventSourceRepository.findByCode(EVENT_SOURCE_MANUAL)
			.orElseThrow(() -> new NotFoundException("Event source not found: " + EVENT_SOURCE_MANUAL))
			.getId();

		// 두 이벤트가 같은 시각을 쓰도록 Clock으로 한 번만 계산
		LocalDateTime occurredAt = LocalDateTime.now(clock);

		// 이벤트1: disposed면 폐기분, 아니면(consumed/zeroWaste) 전체 소진분
		BigDecimal event1Amount = STATUS_DISPOSED.equals(effectiveStatus)
			? (request.wasteAmount() != null
				? BigDecimal.valueOf(request.wasteAmount())
				: ProportionalAmountCalculator.calculate(snapshot.amount(), wasteQty, snapshot.initialQuantity()))
			: ProportionalAmountCalculator.calculate(snapshot.amount(), wasteQty, snapshot.initialQuantity());

		ConsumptionEvent event1 = ConsumptionEvent.create(
			inventoryItemId, userId, effectiveStatus, eventSourceId, wasteQty, event1Amount, occurredAt);
		consumptionEventRepository.save(event1);

		// 부분 폐기 시 남은 수량에 대한 consumed 이벤트 추가 (disposed일 때만 해당)
		BigDecimal remainedQty = request.quantity().subtract(wasteQty);
		if (STATUS_DISPOSED.equals(effectiveStatus) && remainedQty.compareTo(BigDecimal.ZERO) > 0) {
			BigDecimal remainedAmount = ProportionalAmountCalculator.calculate(
				snapshot.amount(), remainedQty, snapshot.initialQuantity());
			ConsumptionEvent event2 = ConsumptionEvent.create(
				inventoryItemId, userId, STATUS_CONSUMED, eventSourceId, remainedQty, remainedAmount, occurredAt);
			consumptionEventRepository.save(event2);
		}

		// 응답: wasteQuantity/wasteAmount는 저장된 폐기 이벤트(event1)의 값 (요청값이 아님). consumed면 둘 다 null
		BigDecimal responseWasteQuantity = STATUS_DISPOSED.equals(effectiveStatus) ? wasteQty : null;
		Integer responseWasteAmount = STATUS_DISPOSED.equals(effectiveStatus)
			? toResponseAmount(event1Amount) : null;

		return new UpdateInventoryItemStatusResponse(
			inventoryItemId,
			effectiveStatus,
			updatedItem.remainingQuantity(),
			responseWasteQuantity,
			responseWasteAmount);
	}

	private Integer toResponseAmount(BigDecimal amount) {
		return amount == null ? null : amount.setScale(0, RoundingMode.HALF_UP).intValueExact();
	}

	/**
	 * 레시피 완료 시 사용분(consumed)과 폐기분(disposed)을 함께 기록한다.
	 * terminal이면 재고 상태를 consumed로 종료하고, 아니면 상태는 active로 유지한 채 남은 수량만 줄인다.
	 */
	@Transactional
	public void recordRecipeUsage(Long userId, Long inventoryItemId, BigDecimal usedQuantity,
			BigDecimal wasteQuantity, boolean terminal) {
		InventoryItemSnapshot snapshot = inventoryService.getSnapshotForUpdate(userId, inventoryItemId);

		BigDecimal totalDeduction = usedQuantity.add(wasteQuantity);
		if (totalDeduction.compareTo(snapshot.remainingQuantity()) > 0) {
			throw new InvalidRequestException("usedQuantity와 wasteQuantity의 합은 남은 수량을 초과할 수 없습니다.");
		}

		if (terminal) {
			inventoryService.updateStatus(userId, inventoryItemId, totalDeduction, getStatusIdByCode(STATUS_CONSUMED));
		} else {
			inventoryService.decreaseRemainingQuantity(userId, inventoryItemId, totalDeduction);
		}

		Short eventSourceId = eventSourceRepository.findByCode(EVENT_SOURCE_MANUAL)
			.orElseThrow(() -> new NotFoundException("Event source not found: " + EVENT_SOURCE_MANUAL))
			.getId();
		LocalDateTime occurredAt = LocalDateTime.now(clock);

		if (usedQuantity.signum() > 0) {
			BigDecimal consumedAmount = ProportionalAmountCalculator.calculate(
				snapshot.amount(), usedQuantity, snapshot.initialQuantity());
			consumptionEventRepository.save(ConsumptionEvent.create(
				inventoryItemId, userId, STATUS_CONSUMED, eventSourceId, usedQuantity, consumedAmount, occurredAt));
		}

		if (wasteQuantity.signum() > 0) {
			BigDecimal wasteAmount = ProportionalAmountCalculator.calculate(
				snapshot.amount(), wasteQuantity, snapshot.initialQuantity());
			consumptionEventRepository.save(ConsumptionEvent.create(
				inventoryItemId, userId, STATUS_DISPOSED, eventSourceId, wasteQuantity, wasteAmount, occurredAt));
		}
	}

	@Transactional(readOnly = true)
	public GetConsumptionEventsResponse getEvents(Long userId, LocalDate from, LocalDate to, String eventType) {
		if (eventType != null && !STATUS_CONSUMED.equals(eventType) && !STATUS_DISPOSED.equals(eventType)) {
			throw new InvalidRequestException("eventType must be 'consumed' or 'disposed'");
		}
		return new GetConsumptionEventsResponse(
			consumptionEventQueryRepository.findEvents(userId, from, to, eventType));
	}

	private void validate(UpdateInventoryItemStatusRequest request, InventoryItemSnapshot snapshot) {
		if (request.status() == null || (!STATUS_CONSUMED.equals(request.status()) &&
				!STATUS_DISPOSED.equals(request.status()))) {
			throw new InvalidRequestException("Status must be 'consumed' or 'disposed'");
		}

		if (request.quantity() == null || request.quantity().compareTo(BigDecimal.ZERO) <= 0) {
			throw new InvalidRequestException("Quantity must be greater than 0");
		}

		if (request.quantity().scale() > 3) {
			throw new InvalidRequestException("Quantity must have at most 3 decimal places");
		}

		if (request.quantity().compareTo(snapshot.remainingQuantity()) > 0) {
			throw new InvalidRequestException("Quantity must not exceed remaining quantity");
		}

		// consumed 상태에서는 wasteQuantity/wasteAmount를 무시하므로 검증하지 않는다.
		if (STATUS_DISPOSED.equals(request.status())) {
			boolean isZeroWaste = request.wasteQuantity() != null
				&& request.wasteQuantity().compareTo(BigDecimal.ZERO) == 0;

			BigDecimal wasteQty = request.wasteQuantity() != null ? request.wasteQuantity() : request.quantity();

			// wasteQuantity=0은 유일한 예외: consumed로 처리되므로 하한 검증을 건너뛴다.
			if (!isZeroWaste && wasteQty.compareTo(BigDecimal.ZERO) <= 0) {
				throw new InvalidRequestException("wasteQuantity must be greater than 0");
			}

			if (wasteQty.compareTo(request.quantity()) > 0) {
				throw new InvalidRequestException("wasteQuantity must not exceed quantity");
			}

			if (request.wasteQuantity() != null && request.wasteQuantity().scale() > 3) {
				throw new InvalidRequestException("wasteQuantity must have at most 3 decimal places");
			}

			if (request.wasteAmount() != null && request.wasteAmount() < 0) {
				throw new InvalidRequestException("wasteAmount must be non-negative");
			}
		}
	}

	private Short getStatusIdByCode(String code) {
		return itemStatusRepository.findByCode(code)
			.map(status -> status.getId())
			.orElseThrow(() -> new NotFoundException("Status not found: " + code));
	}
}
