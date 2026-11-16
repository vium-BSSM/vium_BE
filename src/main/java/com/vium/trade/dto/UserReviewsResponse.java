package com.vium.trade.dto;

import java.math.BigDecimal;
import java.util.List;

public record UserReviewsResponse(Long userId, BigDecimal mannerScore, long reviewCount,
	List<ReviewItem> reviews, String nextCursor, boolean hasNext) {}
