package com.vium.ingredient.controller;

import com.vium.global.common.ApiResponse;
import com.vium.ingredient.dto.IngredientCatalogSearchResponse;
import com.vium.ingredient.service.IngredientCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ingredient-catalogs")
@RequiredArgsConstructor
public class IngredientCatalogController {

	private final IngredientCatalogService ingredientCatalogService;

	@GetMapping
	public ApiResponse<IngredientCatalogSearchResponse> search(
		@RequestParam(defaultValue = "") String keyword) {
		var catalogs = ingredientCatalogService.searchByKeyword(keyword);
		return ApiResponse.ok(IngredientCatalogSearchResponse.from(catalogs));
	}
}
