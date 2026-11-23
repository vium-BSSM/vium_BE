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
 * 장보기 도우미: 지난달(후보가 없으면 최근 3개월 누적) 소진·폐기 이력을 기반으로
 * 추천 후보를 정하고, 소진량과 현재 보유량을 비교해 구매 추천 수량을 계산한다.
 *
 * 후보 판정은 소진(consumed) 또는 폐기(disposed) 이벤트 중 하나라도 있으면 성립한다.
 * 폐기 이벤트가 전혀 없는 품목(예: 매달 다 먹는 계란)도 후보가 되어야
 * "장보기 도우미"의 취지(다음에 뭘 사야 하는지 안내)에 맞는다.
 *
 * toBuy 추천 수량은 기준 기간 낭비 비율(폐기량 ÷ (소진량+폐기량))만큼 줄여서 계산한다.
 * 폐기가 없으면 비율이 1이 되어 shortage를 그대로 추천한다. 낱개로 세는 단위
 * (개/팩/봉/병/캔/통)는 소수점이 있으면 올림해 정수로 보여준다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShoppingHelperService {

	private static final String EVENT_TYPE_DISPOSED = "disposed";
	private static final String EVENT_TYPE_CONSUMED = "consumed";
	private static final int QUANTITY_SCALE = 3;
	private static final int FALLBACK_LOOKBACK_MONTHS = 3;
	private static final Set<Short> COUNT_UNIT_IDS =
		Set.of((short) 1, (short) 6, (short) 7, (short) 8, (short) 9, (short) 10);
	private static final String UNAVAILABLE_REASON = "최근 먹거나 산 재료 기록이 없습니다";

	private final ShoppingEventQueryRepository shoppingEventQueryRepository;
	private final InventoryService inventoryService;
	private final Clock clock;

	public ShoppingHelperResponse getShoppingHelper(Long userId) {
		YearMonth lastCompletedMonth = getLastCompletedMonth();
		List<ActiveIngredientState> inventory = inventoryService.listActiveStates(userId);

		Period period = loadPeriod(userId, lastCompletedMonth, lastCompletedMonth, inventory);
		if (period.candidateItems().isEmpty()) {
			YearMonth fallbackStart = lastCompletedMonth.minusMonths((long) FALLBACK_LOOKBACK_MONTHS - 1);
			period = loadPeriod(userId, fallbackStart, lastCompletedMonth, inventory);
		}

		if (period.candidateItems().isEmpty()) {
			return ShoppingHelperResponse.unavailable(period.to().toString(), UNAVAILABLE_REASON);
		}

		Period effectivePeriod = period;
		Map<ItemKey, BigDecimal> inventoryByKey = getInventoryByKey(inventory);

		List<ClassifiedItem> classified = effectivePeriod.candidateItems().stream()
			.map(key -> {
				BigDecimal consumed = effectivePeriod.consumedByKey().getOrDefault(key, BigDecimal.ZERO);
				BigDecimal disposed = effectivePeriod.disposedByKey().getOrDefault(key, BigDecimal.ZERO);
				BigDecimal currentInv = inventoryByKey.getOrDefault(key, BigDecimal.ZERO);
				BigDecimal shortage = consumed.subtract(currentInv);
				BigDecimal adjustedShortage = applyWasteRatio(shortage, consumed, disposed);
				return new ClassifiedItem(
					key.ingredientCatalogId(),
					key.ingredientName(),
					key.unitId(),
					effectivePeriod.unitNameByKey().getOrDefault(key, ""),
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
				roundForUnit(item.adjustedShortage, item.unitId),
				item.unitId,
				item.unit
			))
			.toList();

		return new ShoppingHelperResponse(
			true,
			null,
			effectivePeriod.to().toString(),
			effectivePeriod.from().toString(),
			effectivePeriod.to().toString(),
			alreadyHave,
			toBuy
		);
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

	record ClassifiedItem(Long ingredientCatalogId, String ingredientName, Short unitId,
		String unit, BigDecimal shortage, BigDecimal adjustedShortage, BigDecimal currentInventory) {
	}

	record Period(Set<ItemKey> candidateItems, Map<ItemKey, String> unitNameByKey,
		Map<ItemKey, BigDecimal> disposedByKey, Map<ItemKey, BigDecimal> consumedByKey,
		YearMonth from, YearMonth to) {
	}
}
