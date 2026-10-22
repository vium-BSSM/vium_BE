package com.vium.dispose.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class ProportionalAmountCalculator {

	private ProportionalAmountCalculator() {
	}

	public static BigDecimal calculate(BigDecimal itemAmount, BigDecimal eventQuantity, BigDecimal initialQuantity) {
		if (itemAmount == null || initialQuantity == null || initialQuantity.compareTo(BigDecimal.ZERO) <= 0) {
			return null;
		}
		return itemAmount
			.multiply(eventQuantity)
			.divide(initialQuantity, 2, RoundingMode.HALF_UP);
	}
}
