package com.vium.trade.dto;

import com.vium.post.entity.PostStatus;
import com.vium.trade.entity.Trade;
import com.vium.trade.entity.TradeStatus;

public record TradeResult(Long tradeId, Long postId, TradeStatus status, PostStatus postStatus,
	Integer price, int heldCredit) {
	public static TradeResult from(Trade trade, PostStatus postStatus) {
		return new TradeResult(trade.getId(), trade.getPostId(), trade.getStatus(), postStatus,
			trade.getPrice(), trade.heldCredit());
	}
}
