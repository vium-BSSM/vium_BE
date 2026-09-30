package com.vium.recipe.dto;

import java.util.List;

public record ImageSearchResponse(
	List<Photo> results
) {
	public record Photo(
		String id,
		String description,
		Urls urls,
		User user
	) {
	}

	public record Urls(
		String raw,
		String full,
		String regular,
		String small,
		String thumb
	) {
	}

	public record User(
		String name,
		String username,
		Links links
	) {
	}

	public record Links(
		String html,
		String photos,
		String likes,
		String portfolio,
		String following,
		String followers
	) {
	}
}
