package com.vium.ingredient.dto;

import com.vium.ingredient.entity.IngredientCatalog;
import java.util.List;

public record IngredientCatalogSearchResponse(
	List<CatalogItem> items
) {

	public record CatalogItem(
		Long ingredientCatalogId,
		String name,
		Short categoryId,
		Short defaultUnitId
	) {

		public static CatalogItem from(IngredientCatalog catalog) {
			return new CatalogItem(
				catalog.getId(),
				catalog.getName(),
				catalog.getCategoryId(),
				catalog.getDefaultUnitId()
			);
		}
	}

	public static IngredientCatalogSearchResponse from(List<IngredientCatalog> catalogs) {
		List<CatalogItem> items = catalogs.stream()
			.map(CatalogItem::from)
			.toList();
		return new IngredientCatalogSearchResponse(items);
	}
}
