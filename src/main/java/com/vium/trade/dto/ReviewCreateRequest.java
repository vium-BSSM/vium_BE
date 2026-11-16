package com.vium.trade.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReviewCreateRequest(@NotNull @Min(1) @Max(5) Integer mannerScore,
	@Size(max = 500) String content) {}
