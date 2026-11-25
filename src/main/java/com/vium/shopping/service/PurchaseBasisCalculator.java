package com.vium.shopping.service;

import com.vium.inventory.dto.ActiveIngredientState;
import com.vium.inventory.service.InventoryService;
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
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class PurchaseBasisCalculator {

	private static final String EVENT_TYPE_DISPOSED = "disposed";
	private static final String EVENT_TYPE_CONSUMED = "consumed";
	private static final int QUANTITY_SCALE = 3;
	private static final int FALLBACK_LOOKBACK_MONTHS = 3;
	private static final Set<Short> COUNT_UNIT_IDS =
		Set.of((short) 1, (short) 6, (short) 7, (short) 8, (short) 9, (short) 10);

	private final ShoppingEventQueryRepository shoppingEventQueryRepository;
	private final InventoryService inventoryService;
	private final Clock clock;

	Basis calculate(Long userId) {
		YearMonth lastCompletedMonth = getLastCompletedMonth();
		List<ActiveIngredientState> inventory = inventoryService.listActiveStates(userId);

		Period period = loadPeriod(userId, lastCompletedMonth, lastCompletedMonth, inventory);
		if (period.candidateItems().isEmpty()) {
			YearMonth fallbackStart = lastCompletedMonth.minusMonths((long) FALLBACK_LOOKBACK_MONTHS - 1);
			period = loadPeriod(userId, fallbackStart, lastCompletedMonth, inventory);
		}

		Period effectivePeriod = period;
		Map<ItemKey, BigDecimal> inventoryByKey = getInventoryByKey(inventory);
		List<ItemBasis> items = effectivePeriod.candidateItems().stream()
			.map(key -> {
				BigDecimal consumed = effectivePeriod.consumedByKey().getOrDefault(key, BigDecimal.ZERO);
				BigDecimal disposed = effectivePeriod.disposedByKey().getOrDefault(key, BigDecimal.ZERO);
				BigDecimal currentInventory = inventoryByKey.getOrDefault(key, BigDecimal.ZERO);
				BigDecimal shortage = consumed.subtract(currentInventory);
				BigDecimal adjustedShortage = applyWasteRatio(shortage, consumed, disposed);
				BigDecimal suggestedQuantity = roundForUnit(adjustedShortage.max(BigDecimal.ZERO), key.unitId());
				boolean hasEvents = effectivePeriod.consumedByKey().containsKey(key)
					|| effectivePeriod.disposedByKey().containsKey(key);
				return new ItemBasis(key, effectivePeriod.unitNameByKey().getOrDefault(key, ""),
					consumed, disposed, currentInventory, shortage, adjustedShortage, suggestedQuantity, hasEvents);
			})
			.toList();
		return new Basis(period.from(), period.to(), items);
	}

	private YearMonth getLastCompletedMonth() {
		LocalDate today = LocalDate.now(clock);
		return YearMonth.of(today.getYear(), today.getMonth()).minusMonths(1);
	}

	/**
	 * from~to(둘 다 포함) 사이 소진·폐기 이벤트를 모아 후보 품목을 구성한다.
	 * 소진 또는 폐기 이벤트가 하나라도 있는 품목이 후보가 된다.
	 *
	 * 이벤트가 없어도, 같은 기간에 구매(purchased_on)했고 현재 active이며
	 * 남은 양이 0보다 큰 재고는 후보에 포함한다(최근 산 뒤 아직 소진·폐기 이벤트가 없는 품목도 추천 대상).
	 */
	private Period loadPeriod(Long userId, YearMonth from, YearMonth to, List<ActiveIngredientState> inventory) {
		LocalDate fromDate = from.atDay(1);
		LocalDate toDate = to.atEndOfMonth();

		List<EventWithItemInfo> disposedEvents =
			shoppingEventQueryRepository.findEventsWithItemInfo(userId, fromDate, toDate, EVENT_TYPE_DISPOSED);
		List<EventWithItemInfo> consumedEvents =
			shoppingEventQueryRepository.findEventsWithItemInfo(userId, fromDate, toDate, EVENT_TYPE_CONSUMED);

		Set<ItemKey> candidateItems = new HashSet<>();
		Map<ItemKey, String> unitNameByKey = new HashMap<>();
		Map<ItemKey, BigDecimal> disposedByKey = new HashMap<>();
		Map<ItemKey, BigDecimal> consumedByKey = new HashMap<>();

		for (EventWithItemInfo event : disposedEvents) {
			if (event.quantity().compareTo(BigDecimal.ZERO) > 0) {
				ItemKey key = new ItemKey(event.ingredientCatalogId(), event.ingredientName(), event.unitId());
				candidateItems.add(key);
				unitNameByKey.putIfAbsent(key, event.unitName());
				disposedByKey.merge(key, event.quantity(), BigDecimal::add);
			}
		}

		for (EventWithItemInfo event : consumedEvents) {
			if (event.quantity().compareTo(BigDecimal.ZERO) > 0) {
				ItemKey key = new ItemKey(event.ingredientCatalogId(), event.ingredientName(), event.unitId());
				candidateItems.add(key);
				unitNameByKey.putIfAbsent(key, event.unitName());
				consumedByKey.merge(key, event.quantity(), BigDecimal::add);
			}
		}

		for (ActiveIngredientState item : inventory) {
			if (item.remainingQuantity().compareTo(BigDecimal.ZERO) > 0
				&& item.purchasedOn() != null
				&& !item.purchasedOn().isBefore(fromDate)
				&& !item.purchasedOn().isAfter(toDate)) {
				ItemKey key = new ItemKey(item.ingredientCatalogId(), item.name(), item.unitId());
				candidateItems.add(key);
				unitNameByKey.putIfAbsent(key, item.unitName());
			}
		}

		return new Period(candidateItems, unitNameByKey, disposedByKey, consumedByKey, from, to);
	}

	/**
	 * shortage(부족량)에 기준 기간 낭비 비율만큼 할인을 적용한다.
	 * adjusted = shortage × consumed ÷ (consumed + disposed)
	 * 예: 소진 5, 폐기 2 → 낭비 비율 2/7 ≈ 28.6% → shortage의 71.4%만 추천.
	 * 폐기가 없으면(disposed=0) 비율은 1이 되어 shortage를 그대로 추천한다.
	 */
	private BigDecimal applyWasteRatio(BigDecimal shortage, BigDecimal consumed, BigDecimal disposed) {
		BigDecimal totalUsed = consumed.add(disposed);
		if (totalUsed.compareTo(BigDecimal.ZERO) <= 0) {
			return shortage;
		}
		return shortage.multiply(consumed).divide(totalUsed, QUANTITY_SCALE, RoundingMode.HALF_UP);
	}

	/**
	 * 낱개로 세는 단위(개/팩/봉/병/캔/통)는 소수점이 조금만 있어도 올림해 정수로 보여준다.
	 * 무게·부피 단위(g/kg/ml/L)는 기존처럼 소수점 3자리를 유지한다.
	 */
	private BigDecimal roundForUnit(BigDecimal value, Short unitId) {
		if (COUNT_UNIT_IDS.contains(unitId)) {
			return value.setScale(0, RoundingMode.CEILING);
		}
		return value;
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

	record Basis(YearMonth from, YearMonth to, List<ItemBasis> items) {
	}

	record ItemBasis(ItemKey key, String unitName, BigDecimal consumed, BigDecimal disposed,
		BigDecimal currentInventory, BigDecimal shortage, BigDecimal adjustedShortage,
		BigDecimal suggestedQuantity, boolean hasEvents) {
	}

	private record Period(Set<ItemKey> candidateItems, Map<ItemKey, String> unitNameByKey,
		Map<ItemKey, BigDecimal> disposedByKey, Map<ItemKey, BigDecimal> consumedByKey,
		YearMonth from, YearMonth to) {
	}
}
