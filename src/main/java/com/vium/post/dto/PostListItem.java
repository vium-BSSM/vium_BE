package com.vium.post.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record PostListItem(
	Long postId,
	String type,
	String status,
	String title,
	String thumbnailUrl,
	String ingredientName,
	BigDecimal quantity,
	String unit,
	LocalDate expiresOn,
	int price,
	int distanceMeters,
	int likeCount,
	boolean likedByMe,
	OffsetDateTime createdAt
) {
}
