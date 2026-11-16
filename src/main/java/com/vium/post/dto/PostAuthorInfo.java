package com.vium.post.dto;

import java.math.BigDecimal;

public record PostAuthorInfo(
	Long userId,
	String displayName,
	String profileImageUrl,
	BigDecimal mannerScore,
	long reviewCount
) {
}
