package com.vium.trade.dto;

import com.vium.post.entity.PostStatus;
import com.vium.trade.entity.Trade;
import com.vium.trade.entity.TradeStatus;

public record TradeCompleteResult(Long tradeId, Long postId, TradeStatus status, PostStatus postStatus,
	Integer price, int heldCredit, Long buyerInventoryItemId, int creditTransferred) {
	public static TradeCompleteResult from(Trade trade, PostStatus postStatus) {
		return new TradeCompleteResult(trade.getId(), trade.getPostId(), trade.getStatus(), postStatus,
			trade.getPrice(), trade.heldCredit(), trade.getBuyerInventoryItemId(), trade.getPrice());
	}
}
