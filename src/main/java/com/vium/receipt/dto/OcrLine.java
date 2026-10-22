package com.vium.receipt.dto;

/** 원본 이미지 픽셀 좌표의 ML Kit Line. 요청 검증은 scan 경계에서 수행한다. */
public record OcrLine(String text, int left, int top, int right, int bottom) {
}
