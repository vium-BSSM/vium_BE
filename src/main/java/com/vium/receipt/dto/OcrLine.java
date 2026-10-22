package com.vium.receipt.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 원본 이미지 픽셀 좌표의 ML Kit Line. */
public record OcrLine(
		@NotNull @Size(min = 1, max = 200) String text,
		@NotNull @Min(0) Integer left,
		@NotNull @Min(0) Integer top,
		@NotNull @Min(0) Integer right,
		@NotNull @Min(0) Integer bottom) {
}
