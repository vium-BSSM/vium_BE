package com.vium.trade.service;

import com.vium.trade.entity.Trade;

/**
 * W6 구현체를 List로 주입한다. 구현체가 없으면 알림은 생략하며 상태 변경 트랜잭션에 합류한다.
 * actorId는 상태를 바꾼 사용자 ID이며, 행위자가 없는 시스템 처리(게시글 만료, 자동 완료, 자동 완료 임박)는 null이다.
 */
public interface TradeChatNotifier {
	void notify(Trade trade, TradeSystemEvent event, Long actorId);
}
