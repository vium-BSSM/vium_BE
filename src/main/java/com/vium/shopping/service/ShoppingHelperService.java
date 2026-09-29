package com.vium.shopping.service;

import com.vium.dispose.dto.ConsumptionEventDto;
import com.vium.dispose.repository.ConsumptionEventQueryRepository;
import com.vium.inventory.dto.IngredientListResponse;
import com.vium.inventory.service.InventoryService;
import com.vium.shopping.dto.response.ShoppingHelperResponse;
import com.vium.shopping.dto.response.ShoppingHelperResponse.InventoryItemDto;
import com.vium.shopping.dto.response.ShoppingHelperResponse.SuggestionItemDto;
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

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShoppingHelperService {

	private final ConsumptionEventQueryRepository consumptionEventQueryRepository;
	private final InventoryService inventoryService;
	private final Clock clock;

	public ShoppingHelperResponse getShoppingHelper(Long userId) {
		YearMonth lastCompletedMonth = getLastCompletedMonth();
		String basedOnReportMonth = lastCompletedMonth.toString();

		boolean hasDisposedEvents = hasConsumptionEventsInMonth(userId, lastCompletedMonth);
		if (!hasDisposedEvents) {
			return ShoppingHelperResponse.unavailable(basedOnReportMonth);
		}

		LocalDate from = lastCompletedMonth.atDay(1);
		LocalDate to = lastCompletedMonth.atEndOfMonth();

		Set<ItemKey> candidateItems = getCandidateItems(userId, from, to);
		if (candidateItems.isEmpty()) {
			return ShoppingHelperResponse.unavailable(basedOnReportMonth);
		}

		List<ClassifiedItem> classified = classifyItems(userId, from, to, candidateItems);

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

	private boolean hasConsumptionEventsInMonth(Long userId, YearMonth month) {
		LocalDate from = month.atDay(1);
		LocalDate to = month.atEndOfMonth();

		List<ConsumptionEventDto> events = consumptionEventQueryRepository.findEvents(
			userId, from, to, "disposed");

		return events.stream()
			.anyMatch(event -> event.quantity().compareTo(BigDecimal.ZERO) > 0);
	}

	private Set<ItemKey> getCandidateItems(Long userId, LocalDate from, LocalDate to) {
		IngredientListResponse inventory = inventoryService.list(userId, false);
		Map<Long, IngredientListResponse.Item> itemMap = new HashMap<>();
		for (IngredientListResponse.Item item : inventory.ingredients()) {
			itemMap.put(item.inventoryItemId(), item);
		}

		Set<ItemKey> candidates = new HashSet<>();
		List<ConsumptionEventDto> disposedEvents = consumptionEventQueryRepository.findEvents(
			userId, from, to, "disposed");

		for (ConsumptionEventDto event : disposedEvents) {
			if (event.quantity().compareTo(BigDecimal.ZERO) > 0) {
				IngredientListResponse.Item item = itemMap.get(event.inventoryItemId());
				if (item != null) {
					ItemKey key = makeKey(item);
					candidates.add(key);
				}
			}
		}

		return candidates;
	}

	private List<ClassifiedItem> classifyItems(Long userId, LocalDate from, LocalDate to, Set<ItemKey> candidateItems) {
		IngredientListResponse inventory = inventoryService.list(userId, false);

		Map<ItemKey, BigDecimal> consumedByKey = getConsumedByKey(userId, from, to);
		Map<ItemKey, BigDecimal> inventoryByKey = getInventoryByKey(inventory);

		Map<ItemKey, ClassifiedItem> result = new HashMap<>();

		for (IngredientListResponse.Item item : inventory.ingredients()) {
			ItemKey key = makeKey(item);
			if (!candidateItems.contains(key)) {
				continue;
			}

			BigDecimal consumed = consumedByKey.getOrDefault(key, BigDecimal.ZERO);
			BigDecimal currentInv = inventoryByKey.getOrDefault(key, BigDecimal.ZERO);
			BigDecimal shortage = consumed.subtract(currentInv);

			result.put(key, new ClassifiedItem(
				item.ingredientCatalogId(),
				item.ingredientCatalogId() != null ? item.name() : item.name(),
				item.unitId(),
				item.unit(),
				shortage,
				currentInv
			));
		}

		for (ItemKey key : consumedByKey.keySet()) {
			if (!result.containsKey(key) && candidateItems.contains(key)) {
				BigDecimal consumed = consumedByKey.get(key);
				result.put(key, new ClassifiedItem(
					key.ingredientCatalogId,
					key.ingredientName,
					key.unitId,
					"",
					consumed,
					BigDecimal.ZERO
				));
			}
		}

		return result.values().stream().toList();
	}

	private Map<ItemKey, BigDecimal> getConsumedByKey(Long userId, LocalDate from, LocalDate to) {
		IngredientListResponse inventory = inventoryService.list(userId, false);
		Map<Long, IngredientListResponse.Item> itemMap = new HashMap<>();
		for (IngredientListResponse.Item item : inventory.ingredients()) {
			itemMap.put(item.inventoryItemId(), item);
		}

		Map<ItemKey, BigDecimal> result = new HashMap<>();
		List<ConsumptionEventDto> events = consumptionEventQueryRepository.findEvents(
			userId, from, to, "consumed");

		for (ConsumptionEventDto event : events) {
			IngredientListResponse.Item item = itemMap.get(event.inventoryItemId());
			if (item != null) {
				ItemKey key = makeKey(item);
				BigDecimal current = result.getOrDefault(key, BigDecimal.ZERO);
				result.put(key, current.add(event.quantity()));
			}
		}

		return result;
	}

	private Map<ItemKey, BigDecimal> getInventoryByKey(IngredientListResponse inventory) {
		Map<ItemKey, BigDecimal> result = new HashMap<>();

		for (IngredientListResponse.Item item : inventory.ingredients()) {
			if (item.remainingQuantity().compareTo(BigDecimal.ZERO) > 0) {
				ItemKey key = makeKey(item);
				BigDecimal current = result.getOrDefault(key, BigDecimal.ZERO);
				result.put(key, current.add(item.remainingQuantity()));
			}
		}

		return result;
	}

	private ItemKey makeKey(IngredientListResponse.Item item) {
		String name = item.ingredientCatalogId() != null ? item.name() : item.name();
		return new ItemKey(item.ingredientCatalogId(), name, item.unitId());
	}

	record ItemKey(Long ingredientCatalogId, String ingredientName, Short unitId) {
	}

	record ClassifiedItem(Long ingredientCatalogId, String ingredientName, Short unitId,
		String unit, BigDecimal shortage, BigDecimal currentInventory) {
	}
}
