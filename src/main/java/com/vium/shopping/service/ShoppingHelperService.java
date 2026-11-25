package com.vium.shopping.service;

import com.vium.shopping.dto.response.ShoppingHelperResponse;
import com.vium.shopping.dto.response.ShoppingHelperResponse.InventoryItemDto;
import com.vium.shopping.dto.response.ShoppingHelperResponse.SuggestionItemDto;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShoppingHelperService {

	private static final String UNAVAILABLE_REASON = "최근 먹거나 산 재료 기록이 없습니다";

	private final PurchaseBasisCalculator calculator;

	public ShoppingHelperResponse getShoppingHelper(Long userId) {
		var basis = calculator.calculate(userId);
		if (basis.items().isEmpty()) {
			return ShoppingHelperResponse.unavailable(basis.to().toString(), UNAVAILABLE_REASON);
		}

		List<InventoryItemDto> alreadyHave = basis.items().stream()
			.filter(item -> item.shortage().compareTo(BigDecimal.ZERO) <= 0
				&& item.currentInventory().compareTo(BigDecimal.ZERO) > 0)
			.map(item -> new InventoryItemDto(
				item.key().ingredientCatalogId(),
				item.key().ingredientName(),
				item.currentInventory(),
				item.key().unitId(),
				item.unitName()
			))
			.toList();

		List<SuggestionItemDto> toBuy = basis.items().stream()
			.filter(item -> item.shortage().compareTo(BigDecimal.ZERO) > 0
				&& item.adjustedShortage().compareTo(BigDecimal.ZERO) > 0)
			.map(item -> new SuggestionItemDto(
				item.key().ingredientCatalogId(),
				item.key().ingredientName(),
				item.suggestedQuantity(),
				item.key().unitId(),
				item.unitName()
			))
			.toList();

		return new ShoppingHelperResponse(
			true,
			null,
			basis.to().toString(),
			basis.from().toString(),
			basis.to().toString(),
			alreadyHave,
			toBuy
		);
	}
}
