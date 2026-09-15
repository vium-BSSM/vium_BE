package com.vium.dispose.presentation.dto;

import java.util.List;

public record GetWastePatternsResponse(
	List<WastePatternDto> patterns
) {
}
