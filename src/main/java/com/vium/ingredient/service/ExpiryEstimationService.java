package com.vium.ingredient.service;

import com.vium.ingredient.repository.ExpiryEstimationRuleRepository;
import java.time.LocalDate;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ExpiryEstimationService {

	private final ExpiryEstimationRuleRepository expiryEstimationRuleRepository;

	public Optional<LocalDate> estimateExpiresOn(Long ingredientCatalogId, Short storageMethodId, LocalDate purchasedOn) {
		if (ingredientCatalogId == null || storageMethodId == null) {
			return Optional.empty();
		}
		return expiryEstimationRuleRepository
			.findApplicableRules(ingredientCatalogId, storageMethodId, purchasedOn)
			.stream()
			.findFirst()
			.map(rule -> purchasedOn.plusDays(rule.getShelfLifeDays()));
	}
}
