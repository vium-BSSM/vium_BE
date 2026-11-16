package com.vium.trade.service;

import com.vium.trade.entity.Trade;

/** W6 구현체를 List로 주입한다. 구현체가 없으면 알림은 생략하며 상태 변경 트랜잭션에 합류한다. */
public interface TradeChatNotifier {
	void notify(Trade trade, TradeSystemEvent event);
}
