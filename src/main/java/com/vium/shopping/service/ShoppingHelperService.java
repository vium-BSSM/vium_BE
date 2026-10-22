package com.vium.shopping.service;

import com.vium.inventory.dto.IngredientListResponse;
import com.vium.inventory.service.InventoryService;
import com.vium.shopping.dto.response.ShoppingHelperResponse;
import com.vium.shopping.dto.response.ShoppingHelperResponse.InventoryItemDto;
import com.vium.shopping.dto.response.ShoppingHelperResponse.SuggestionItemDto;
import com.vium.shopping.repository.ShoppingEventQueryRepository;
import com.vium.shopping.repository.ShoppingEventQueryRepository.EventWithItemInfo;
import java.math.BigDecimal;
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
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShoppingHelperService {

	private static final String EVENT_TYPE_DISPOSED = "disposed";
	private static final String EVENT_TYPE_CONSUMED = "consumed";

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
		for (EventWithItemInfo event : disposedEvents) {
			if (event.quantity().compareTo(BigDecimal.ZERO) > 0) {
				ItemKey key = new ItemKey(event.ingredientCatalogId(), event.ingredientName(), event.unitId());
				candidateItems.add(key);
				unitNameByKey.putIfAbsent(key, event.unitName());
			}
		}

		if (candidateItems.isEmpty()) {
			return ShoppingHelperResponse.unavailable(basedOnReportMonth);
		}

		Map<ItemKey, BigDecimal> consumedByKey = getConsumedByKey(userId, from, to, unitNameByKey);

		IngredientListResponse inventory = inventoryService.list(userId, false);
		Map<ItemKey, BigDecimal> inventoryByKey = getInventoryByKey(inventory);

		List<ClassifiedItem> classified = candidateItems.stream()
			.map(key -> {
				BigDecimal consumed = consumedByKey.getOrDefault(key, BigDecimal.ZERO);
				BigDecimal currentInv = inventoryByKey.getOrDefault(key, BigDecimal.ZERO);
				BigDecimal shortage = consumed.subtract(currentInv);
				return new ClassifiedItem(
					key.ingredientCatalogId(),
					key.ingredientName(),
					key.unitId(),
					unitNameByKey.getOrDefault(key, ""),
					shortage,
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
			.filter(item -> item.shortage.compareTo(BigDecimal.ZERO) > 0)
			.map(item -> new SuggestionItemDto(
				item.ingredientCatalogId,
				item.ingredientName,
				item.shortage,
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
	private Map<ItemKey, BigDecimal> getInventoryByKey(IngredientListResponse inventory) {
		Map<ItemKey, BigDecimal> result = new HashMap<>();

		for (IngredientListResponse.Item item : inventory.ingredients()) {
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
		String unit, BigDecimal shortage, BigDecimal currentInventory) {
	}
}
