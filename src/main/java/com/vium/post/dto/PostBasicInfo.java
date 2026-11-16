package com.vium.post.dto;

public record PostBasicInfo(
	Long postId,
	Long authorId,
	String title,
	String thumbnailUrl,
	String status,
	Long inventoryItemId,
	String type,
	Integer price
) {
}
