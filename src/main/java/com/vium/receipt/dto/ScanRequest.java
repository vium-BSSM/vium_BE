package com.vium.receipt.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ScanRequest(
		@Positive Long imageId,
		@NotNull @Min(1) @Max(10000) Integer imageWidth,
		@NotNull @Min(1) @Max(10000) Integer imageHeight,
		@NotNull @Size(min = 1, max = 300) List<@NotNull @Valid OcrLine> lines) {
}
