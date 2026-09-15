package com.vium.dispose.presentation.dto;

public record WastePatternDto(
	Long categoryId,
	String categoryName,
	Long wasteCount,
	Long totalWastedAmount,
	Double wasteRatio
) {
}
