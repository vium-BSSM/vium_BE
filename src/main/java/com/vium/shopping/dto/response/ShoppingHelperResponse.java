package com.vium.shopping.dto.response;

import java.util.List;

public record ShoppingHelperResponse(
	Boolean available,
	String reason,
	String basedOnReportMonth,
	String basedOnPeriodStart,
	String basedOnPeriodEnd,
	List<InventoryItemDto> alreadyHave,
	List<SuggestionItemDto> toBuy
) {

	public record InventoryItemDto(
		Long ingredientCatalogId,
		String ingredientName,
		java.math.BigDecimal quantity,
		Short unitId,
		String unit
	) {
	}

	public record SuggestionItemDto(
		Long ingredientCatalogId,
		String ingredientName,
		java.math.BigDecimal suggestedQuantity,
		Short unitId,
		String unit
	) {
	}

	public static ShoppingHelperResponse unavailable(String basedOnReportMonth, String reason) {
		return new ShoppingHelperResponse(
			false,
			reason,
			basedOnReportMonth,
			null,
			null,
			List.of(),
			List.of()
		);
	}
}
