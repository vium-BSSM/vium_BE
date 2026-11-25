package com.vium.shopping.service;

import com.vium.shopping.dto.response.PurchaseSuggestionResponse;
import com.vium.shopping.dto.response.PurchaseSuggestionResponse.Adjustment;
import com.vium.shopping.dto.response.PurchaseSuggestionResponse.Item;
import com.vium.shopping.service.PurchaseBasisCalculator.ItemBasis;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PurchaseSuggestionService {

	private static final BigDecimal HALF = new BigDecimal("0.5");
	private static final BigDecimal SOME = new BigDecimal("0.2");
	private static final Comparator<Item> DISPLAY_ORDER = Comparator.comparing(Item::wasteRatio).reversed()
		.thenComparing(Item::ingredientName)
		.thenComparing(Item::unitId);

	private final PurchaseBasisCalculator calculator;

	public PurchaseSuggestionResponse getPurchaseSuggestions(Long userId) {
		var basis = calculator.calculate(userId);
		List<Item> items = basis.items().stream()
			.filter(ItemBasis::hasEvents)
			.map(this::toItem)
			.sorted(DISPLAY_ORDER)
			.toList();
		if (items.isEmpty()) {
			return new PurchaseSuggestionResponse(false, null, null, items);
		}
		return new PurchaseSuggestionResponse(true, basis.from().toString(), basis.to().toString(), items);
	}

	private Item toItem(ItemBasis item) {
		BigDecimal wasteRatio = item.disposed().divide(item.consumed().add(item.disposed()), 2, RoundingMode.HALF_UP);
		Adjustment adjustment = adjustmentFor(item, wasteRatio);
		return new Item(item.key().ingredientCatalogId(), item.key().ingredientName(), item.key().unitId(),
			item.unitName(), item.consumed(), item.disposed(), item.currentInventory(), wasteRatio,
			item.suggestedQuantity(), adjustment, adjustment.message());
	}

	private Adjustment adjustmentFor(ItemBasis item, BigDecimal wasteRatio) {
		if (item.suggestedQuantity().signum() == 0 && item.currentInventory().signum() > 0) {
			return Adjustment.HAVE_ENOUGH;
		}
		if (item.suggestedQuantity().signum() == 0) {
			return Adjustment.SKIP;
		}
		if (wasteRatio.compareTo(HALF) >= 0) {
			return Adjustment.REDUCE_HALF;
		}
		if (wasteRatio.compareTo(SOME) >= 0) {
			return Adjustment.REDUCE_SOME;
		}
		return Adjustment.KEEP;
	}
}
