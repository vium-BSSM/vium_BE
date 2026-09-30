package com.vium.recipe.dto;

public record ImageSearchResult(
	String imageUrl,
	String authorName,
	String authorUrl,
	String downloadUrl
) {
}
