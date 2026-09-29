package com.vium.shopping.dto.response;

import java.util.List;

public record ShoppingHelperResponse(
	Boolean available,
	String reason,
	String basedOnReportMonth,
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

	public static ShoppingHelperResponse unavailable(String basedOnReportMonth) {
		return new ShoppingHelperResponse(
			false,
			"최신 리포트가 없습니다",
			basedOnReportMonth,
			List.of(),
			List.of()
		);
	}
}
