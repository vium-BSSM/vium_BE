package com.vium.dispose.dto;

import java.math.BigDecimal;

public record TopWastedItemDto(
	int rank,
	String name,
	long lossAmount,
	Long purchaseAmount,
	BigDecimal wasteRatio
) {
}
