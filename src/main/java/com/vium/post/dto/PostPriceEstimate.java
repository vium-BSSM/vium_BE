package com.vium.post.dto;

import java.math.BigDecimal;

public record PostPriceEstimate(
	int recommendedPrice,
	int minPrice,
	int maxPrice,
	BigDecimal categoryBaseValue,
	BigDecimal freshnessFactor
) {
}
