package com.vium.ingredient.service;

import com.vium.global.code.StorageMethod;
import com.vium.global.code.StorageMethodRepository;
import com.vium.global.exception.InvalidRequestException;
import com.vium.global.exception.NotFoundException;
import com.vium.ingredient.dto.ExpiryEstimateResponse;
import com.vium.ingredient.repository.ExpiryEstimationRuleRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ExpiryEstimationService {

	private static final List<Short> STORAGE_ORDER = List.of((short) 1, (short) 3, (short) 2);

	private final ExpiryEstimationRuleRepository expiryEstimationRuleRepository;
	private final IngredientCatalogService catalog;
	private final StorageMethodRepository storageMethods;

	public Optional<LocalDate> estimateExpiresOn(Long ingredientCatalogId, Short storageMethodId, LocalDate purchasedOn) {
		return estimate(ingredientCatalogId, storageMethodId, purchasedOn).map(ExpiryEstimate::expiresOn);
	}

	public Optional<ExpiryEstimate> estimateWithDefaultStorage(Long ingredientCatalogId, LocalDate purchasedOn) {
		if (ingredientCatalogId == null) {
			return Optional.empty();
		}
		for (Short storageMethodId : STORAGE_ORDER) {
			var estimate = estimate(ingredientCatalogId, storageMethodId, purchasedOn);
			if (estimate.isPresent()) {
				return estimate;
			}
		}
		return Optional.empty();
	}

	@Transactional(readOnly = true)
	public ExpiryEstimateResponse preview(Long ingredientCatalogId, Short storageMethodId, LocalDate purchasedOn) {
		if (!catalog.existsById(ingredientCatalogId)) {
			throw new NotFoundException("존재하지 않는 ingredientCatalogId 입니다");
		}
		String ingredientName = catalog.getNameById(ingredientCatalogId);

		StorageMethod storage = null;
		ExpiryEstimate result;
		if (storageMethodId == null) {
			result = estimateWithDefaultStorage(ingredientCatalogId, purchasedOn).orElse(null);
			if (result != null) {
				storage = storageMethods.findById(result.storageMethodId()).orElseThrow();
			}
		} else {
			storage = storageMethods.findById(storageMethodId)
				.orElseThrow(() -> new InvalidRequestException("존재하지 않는 storageMethodId 입니다"));
			result = estimate(ingredientCatalogId, storageMethodId, purchasedOn).orElse(null);
		}

		return new ExpiryEstimateResponse(ingredientCatalogId, ingredientName,
			storage == null ? null : storage.getId(), storage == null ? null : storage.getName(), purchasedOn,
			result == null ? null : result.shelfLifeDays(), result == null ? null : result.expiresOn());
	}

	public Optional<ExpiryEstimate> estimate(Long ingredientCatalogId, Short storageMethodId, LocalDate purchasedOn) {
		if (ingredientCatalogId == null || storageMethodId == null) {
			return Optional.empty();
		}
		return expiryEstimationRuleRepository
			.findApplicableRules(ingredientCatalogId, storageMethodId, purchasedOn)
			.stream()
			.findFirst()
			.map(rule -> new ExpiryEstimate(storageMethodId, rule.getShelfLifeDays(),
				purchasedOn.plusDays(rule.getShelfLifeDays())));
	}

	public record ExpiryEstimate(Short storageMethodId, int shelfLifeDays, LocalDate expiresOn) {
	}
}
