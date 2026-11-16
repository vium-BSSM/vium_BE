package com.vium.trade.service;

import com.vium.trade.entity.Trade;

public interface TradeChatNotifier {

	void notify(Trade trade, TradeSystemEvent event);
}
