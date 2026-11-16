package com.vium.ingredient.service;

import com.vium.ingredient.entity.IngredientCatalog;
import com.vium.ingredient.dto.CatalogName;
import com.vium.ingredient.dto.CatalogPostInfo;
import com.vium.ingredient.repository.IngredientCatalogRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IngredientCatalogService {

	private final IngredientCatalogRepository ingredientCatalogRepository;

	@Transactional(readOnly = true)
	public List<CatalogName> findAllNames() {
		return ingredientCatalogRepository.findAllNames();
	}

	@Transactional(readOnly = true)
	public List<CatalogName> findNamesByIds(java.util.Collection<Long> ids) {
		return ids.isEmpty() ? List.of() : ingredientCatalogRepository.findNamesByIds(ids);
	}

	@Transactional(readOnly = true)
	public boolean existsById(Long ingredientCatalogId) {
		return ingredientCatalogRepository.existsById(ingredientCatalogId);
	}

	@Transactional(readOnly = true)
	public String getNameById(Long ingredientCatalogId) {
		return ingredientCatalogRepository.findById(ingredientCatalogId)
			.map(catalog -> catalog.getName())
			.orElse(null);
	}

	@Transactional(readOnly = true)
	public List<IngredientCatalog> searchByKeyword(String keyword) {
		return ingredientCatalogRepository.searchByKeyword(keyword);
	}

	/**
	 * post 도메인이 게시 가능 여부·추천가 계산에 쓰는 카탈로그 정보(카테고리, 게시 금지 품목 여부).
	 */
	@Transactional(readOnly = true)
	public Optional<CatalogPostInfo> findPostInfo(Long ingredientCatalogId) {
		return ingredientCatalogRepository.findById(ingredientCatalogId)
			.map(catalog -> new CatalogPostInfo(catalog.getCategoryId(), Boolean.TRUE.equals(catalog.getPostAllowed())));
	}
}
