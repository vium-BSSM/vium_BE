package com.vium.post.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record PostDetailResponse(
	Long postId,
	String type,
	String status,
	String title,
	String description,
	List<PostImageItem> images,
	PostAuthorInfo author,
	PostIngredientInfo ingredient,
	int price,
	Integer distanceMeters,
	int likeCount,
	boolean likedByMe,
	boolean isMine,
	Long activeTradeId,
	OffsetDateTime createdAt,
	OffsetDateTime updatedAt
) {
}
