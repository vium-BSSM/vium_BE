package com.vium.trade.dto;

import com.vium.post.entity.PostType;
import com.vium.trade.entity.TradeStatus;
import java.time.OffsetDateTime;

public record TradeSummary(Long tradeId, TradeStatus status, TradeRole role, PostInfo post, Partner partner,
	int price, OffsetDateTime requestedAt, OffsetDateTime acceptedAt, OffsetDateTime completedAt,
	OffsetDateTime autoResolveAt, boolean reviewWritten) {
	public record PostInfo(Long postId, PostType type, String title, String thumbnailUrl) {}
	public record Partner(Long userId, String displayName, String profileImageUrl) {}
}
