package com.vium.ingredient.service;

import com.vium.ingredient.repository.IngredientCatalogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IngredientCatalogService {

	private final IngredientCatalogRepository ingredientCatalogRepository;

	@Transactional(readOnly = true)
	public boolean existsById(Long ingredientCatalogId) {
		return ingredientCatalogRepository.existsById(ingredientCatalogId);
	}
}
