package com.vium.post.dto;

import java.math.BigDecimal;

public record MannerStats(BigDecimal mannerScore, long reviewCount) {

	public static final MannerStats EMPTY = new MannerStats(null, 0);
}
