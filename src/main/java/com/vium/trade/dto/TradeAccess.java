package com.vium.trade.dto;

/** 잠금 전 조회는 영속성 컨텍스트에 엔티티를 올리지 않아 오래된 상태의 재사용을 피한다. */
public record TradeAccess(Long postId, Long buyerId, Long sellerId) {
	public boolean isParticipant(Long userId) { return buyerId.equals(userId) || sellerId.equals(userId); }
}
