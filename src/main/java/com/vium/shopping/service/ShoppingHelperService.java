package com.vium.shopping.service;

import com.vium.inventory.dto.ActiveIngredientState;
import com.vium.inventory.service.InventoryService;
import com.vium.shopping.dto.response.ShoppingHelperResponse;
import com.vium.shopping.dto.response.ShoppingHelperResponse.InventoryItemDto;
import com.vium.shopping.dto.response.ShoppingHelperResponse.SuggestionItemDto;
import com.vium.shopping.repository.ShoppingEventQueryRepository;
import com.vium.shopping.repository.ShoppingEventQueryRepository.EventWithItemInfo;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 장보기 도우미: 지난달 폐기 이력을 기반으로 추천 후보를 정하고,
 * 소진량과 현재 보유량을 비교해 구매 추천 수량을 계산한다.
 *
 * 후보 판정과 unavailable 조건은 "폐기 이벤트" 기준이다(docs/PickDo.openapi.json,
 * CLAUDE.md 어디에도 소진 이벤트만으로 후보가 되거나 unavailable 조건을 완화한다는
 * 근거가 없어 원래 동작을 유지한다). 다만 재료 이름/단위는 inventory_items의
 * active 상태 여부와 무관하게 이벤트 조인 결과에서 직접 가져온다(폐기된 재료도
 * 이름/단위가 정상적으로 매핑되어야 하므로).
 *
 * toBuy 추천 수량은 지난달 낭비 비율(폐기량 ÷ (소진량+폐기량))만큼 줄여서 계산한다.
 * 같은 품목을 많이 버렸을수록 "지난달만큼" 사지 않도록 유도한다(기능명세서의
 * "다음 구매량 제안" 취지 반영, 구매 이력이 없어 소진·폐기 이벤트로 근사).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShoppingHelperService {

	private static final String EVENT_TYPE_DISPOSED = "disposed";
	private static final String EVENT_TYPE_CONSUMED = "consumed";
	private static final int QUANTITY_SCALE = 3;

	private final ShoppingEventQueryRepository shoppingEventQueryRepository;
	private final InventoryService inventoryService;
	private final Clock clock;

	public ShoppingHelperResponse getShoppingHelper(Long userId) {
		YearMonth lastCompletedMonth = getLastCompletedMonth();
		String basedOnReportMonth = lastCompletedMonth.toString();

		LocalDate from = lastCompletedMonth.atDay(1);
		LocalDate to = lastCompletedMonth.atEndOfMonth();

		List<EventWithItemInfo> disposedEvents =
			shoppingEventQueryRepository.findEventsWithItemInfo(userId, from, to, EVENT_TYPE_DISPOSED);

		// unavailable 조건: 지난달 폐기 이벤트가 없을 때 (원래 동작)
		if (!hasPositiveQuantity(disposedEvents)) {
			return ShoppingHelperResponse.unavailable(basedOnReportMonth);
		}

		Set<ItemKey> candidateItems = new HashSet<>();
		Map<ItemKey, String> unitNameByKey = new HashMap<>();
		Map<ItemKey, BigDecimal> disposedByKey = new HashMap<>();
		for (EventWithItemInfo event : disposedEvents) {
			if (event.quantity().compareTo(BigDecimal.ZERO) > 0) {
				ItemKey key = new ItemKey(event.ingredientCatalogId(), event.ingredientName(), event.unitId());
				candidateItems.add(key);
				unitNameByKey.putIfAbsent(key, event.unitName());
				disposedByKey.merge(key, event.quantity(), BigDecimal::add);
			}
		}

		if (candidateItems.isEmpty()) {
			return ShoppingHelperResponse.unavailable(basedOnReportMonth);
		}

		Map<ItemKey, BigDecimal> consumedByKey = getConsumedByKey(userId, from, to, unitNameByKey);

		List<ActiveIngredientState> inventory = inventoryService.listActiveStates(userId);
		Map<ItemKey, BigDecimal> inventoryByKey = getInventoryByKey(inventory);

		List<ClassifiedItem> classified = candidateItems.stream()
			.map(key -> {
				BigDecimal consumed = consumedByKey.getOrDefault(key, BigDecimal.ZERO);
				BigDecimal disposed = disposedByKey.getOrDefault(key, BigDecimal.ZERO);
				BigDecimal currentInv = inventoryByKey.getOrDefault(key, BigDecimal.ZERO);
				BigDecimal shortage = consumed.subtract(currentInv);
				BigDecimal adjustedShortage = applyWasteRatio(shortage, consumed, disposed);
				return new ClassifiedItem(
					key.ingredientCatalogId(),
					key.ingredientName(),
					key.unitId(),
					unitNameByKey.getOrDefault(key, ""),
					shortage,
					adjustedShortage,
					currentInv
				);
			})
			.toList();

		List<InventoryItemDto> alreadyHave = classified.stream()
			.filter(item -> item.shortage.compareTo(BigDecimal.ZERO) <= 0
				&& item.currentInventory.compareTo(BigDecimal.ZERO) > 0)
			.map(item -> new InventoryItemDto(
				item.ingredientCatalogId,
				item.ingredientName,
				item.currentInventory,
				item.unitId,
				item.unit
			))
			.toList();

		List<SuggestionItemDto> toBuy = classified.stream()
			.filter(item -> item.shortage.compareTo(BigDecimal.ZERO) > 0
				&& item.adjustedShortage.compareTo(BigDecimal.ZERO) > 0)
			.map(item -> new SuggestionItemDto(
				item.ingredientCatalogId,
				item.ingredientName,
				item.adjustedShortage,
				item.unitId,
				item.unit
			))
			.toList();

		return new ShoppingHelperResponse(
			true,
			null,
			basedOnReportMonth,
			alreadyHave,
			toBuy
		);
	}

	private YearMonth getLastCompletedMonth() {
		LocalDate today = LocalDate.now(clock);
		return YearMonth.of(today.getYear(), today.getMonth()).minusMonths(1);
	}

	private boolean hasPositiveQuantity(List<EventWithItemInfo> events) {
		return events.stream().anyMatch(event -> event.quantity().compareTo(BigDecimal.ZERO) > 0);
	}

	/**
	 * shortage(부족량)에 지난달 낭비 비율만큼 할인을 적용한다.
	 * adjusted = shortage × consumed ÷ (consumed + disposed)
	 * 예: 소진 5, 폐기 2 → 낭비 비율 2/7 ≈ 28.6% → shortage의 71.4%만 추천.
	 */
	private BigDecimal applyWasteRatio(BigDecimal shortage, BigDecimal consumed, BigDecimal disposed) {
		BigDecimal totalUsed = consumed.add(disposed);
		if (totalUsed.compareTo(BigDecimal.ZERO) <= 0) {
			return shortage;
		}
		return shortage.multiply(consumed).divide(totalUsed, QUANTITY_SCALE, RoundingMode.HALF_UP);
	}

	/**
	 * 지난달 소진량을 재료 키별로 집계한다. inventory_items의 active 상태와 무관하게
	 * 이벤트 조인 결과에서 이름/단위를 가져오므로, 소진 후 상태가 바뀐 재료도 올바르게 집계된다.
	 */
	private Map<ItemKey, BigDecimal> getConsumedByKey(Long userId, LocalDate from, LocalDate to,
			Map<ItemKey, String> unitNameByKey) {
		Map<ItemKey, BigDecimal> result = new HashMap<>();
		List<EventWithItemInfo> events =
			shoppingEventQueryRepository.findEventsWithItemInfo(userId, from, to, EVENT_TYPE_CONSUMED);

		for (EventWithItemInfo event : events) {
			ItemKey key = new ItemKey(event.ingredientCatalogId(), event.ingredientName(), event.unitId());
			result.merge(key, event.quantity(), BigDecimal::add);
			unitNameByKey.putIfAbsent(key, event.unitName());
		}

		return result;
	}

	/**
	 * 현재 보유량은 active 재료만으로 계산한다(설계 원칙: 현재 재고 = active 상태).
	 */
	private Map<ItemKey, BigDecimal> getInventoryByKey(List<ActiveIngredientState> inventory) {
		Map<ItemKey, BigDecimal> result = new HashMap<>();

		for (ActiveIngredientState item : inventory) {
			if (item.remainingQuantity().compareTo(BigDecimal.ZERO) > 0) {
				ItemKey key = new ItemKey(item.ingredientCatalogId(), item.name(), item.unitId());
				BigDecimal current = result.getOrDefault(key, BigDecimal.ZERO);
				result.put(key, current.add(item.remainingQuantity()));
			}
		}

		return result;
	}

	record ItemKey(Long ingredientCatalogId, String ingredientName, Short unitId) {
	}

	record ClassifiedItem(Long ingredientCatalogId, String ingredientName, Short unitId,
		String unit, BigDecimal shortage, BigDecimal adjustedShortage, BigDecimal currentInventory) {
	}
}
