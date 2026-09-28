package com.vium.dispose.dto;

import java.util.List;

public record GetWastePatternsResponse(
	List<WastePatternDto> patterns
) {
}
