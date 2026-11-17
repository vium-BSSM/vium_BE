package com.vium.trade.dto;

import java.time.OffsetDateTime;

public record ReviewItem(Long reviewId, Writer writer, int mannerScore, String content, OffsetDateTime createdAt) {
	public record Writer(Long userId, String displayName) {}
}
