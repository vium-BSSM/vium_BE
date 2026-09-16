package com.vium.dispose.dto;

public record WastePatternDto(
	Long categoryId,
	String categoryName,
	Long wasteCount,
	Long totalWastedAmount,
	Double wasteRatio
) {
}
