package com.vium.post.service;

import com.vium.credit.service.CreditService;
import com.vium.ingredient.dto.CatalogPostInfo;
import com.vium.ingredient.service.IngredientCatalogService;
import com.vium.post.config.FreshnessFactorProperties;
import com.vium.post.dto.PostPriceEstimate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 추천가 = round(기준값 x 수량(기준 단위 환산) x 신선도 계수).
 * 기준 단위 환산: g -> kg, ml -> L, 그 외(개·팩·봉·병·캔·통)는 그대로.
 * 단위 ID는 docs/vium.sql 고정값(1=개, 2=g, 3=kg, 4=ml, 5=L, ...)을 그대로 쓴다.
 */
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(FreshnessFactorProperties.class)
public class PostPricingService {

	private static final short GRAM_UNIT_ID = 2;
	private static final short KILOGRAM_UNIT_ID = 3;
	private static final short MILLILITER_UNIT_ID = 4;
	private static final short LITER_UNIT_ID = 5;
	private static final BigDecimal PER_THOUSAND_FACTOR = BigDecimal.valueOf(0.001);
	private static final BigDecimal MIN_PRICE_RATIO = BigDecimal.valueOf(0.8);
	private static final BigDecimal MAX_PRICE_RATIO = BigDecimal.valueOf(1.2);

	private final IngredientCatalogService ingredientCatalogService;
	private final CreditService creditService;
	private final FreshnessFactorProperties freshnessFactorProperties;
	private final Clock clock;

	@Transactional(readOnly = true)
	public Optional<PostPriceEstimate> estimate(Long ingredientCatalogId, Short unitId, BigDecimal quantity,
			LocalDate expiresOn) {
		if (ingredientCatalogId == null) {
			return Optional.empty();
		}
		Short categoryId = ingredientCatalogService.findPostInfo(ingredientCatalogId)
			.map(CatalogPostInfo::categoryId)
			.orElse(null);
		if (categoryId == null) {
			return Optional.empty();
		}

		Short baseUnitId = toBaseUnitId(unitId);
		BigDecimal convertedQuantity = convertQuantity(unitId, quantity);

		return creditService.findBaseValue(categoryId, baseUnitId).map(baseValue -> {
			BigDecimal freshnessFactor = resolveFreshnessFactor(expiresOn);
			int recommendedPrice = baseValue.multiply(convertedQuantity).multiply(freshnessFactor)
				.setScale(0, RoundingMode.HALF_UP).intValueExact();
			return new PostPriceEstimate(recommendedPrice, minPrice(recommendedPrice), maxPrice(recommendedPrice),
				baseValue, freshnessFactor);
		});
	}

	public int minPrice(int recommendedPrice) {
		int candidate = BigDecimal.valueOf(recommendedPrice).multiply(MIN_PRICE_RATIO)
			.setScale(0, RoundingMode.CEILING).intValueExact();
		return Math.max(1, candidate);
	}

	public int maxPrice(int recommendedPrice) {
		return BigDecimal.valueOf(recommendedPrice).multiply(MAX_PRICE_RATIO)
			.setScale(0, RoundingMode.FLOOR).intValueExact();
	}

	private Short toBaseUnitId(Short unitId) {
		if (unitId == GRAM_UNIT_ID) {
			return KILOGRAM_UNIT_ID;
		}
		if (unitId == MILLILITER_UNIT_ID) {
			return LITER_UNIT_ID;
		}
		return unitId;
	}

	private BigDecimal convertQuantity(Short unitId, BigDecimal quantity) {
		if (unitId == GRAM_UNIT_ID || unitId == MILLILITER_UNIT_ID) {
			return quantity.multiply(PER_THOUSAND_FACTOR);
		}
		return quantity;
	}

	private BigDecimal resolveFreshnessFactor(LocalDate expiresOn) {
		if (expiresOn == null) {
			return freshnessFactorProperties.day1OrLess();
		}
		long daysRemaining = ChronoUnit.DAYS.between(LocalDate.now(clock), expiresOn);
		if (daysRemaining >= 7) {
			return freshnessFactorProperties.daysAtLeast7();
		}
		if (daysRemaining >= 4) {
			return freshnessFactorProperties.days4To6();
		}
		if (daysRemaining >= 2) {
			return freshnessFactorProperties.days2To3();
		}
		return freshnessFactorProperties.day1OrLess();
	}
}
