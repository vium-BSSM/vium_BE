package com.vium.trade.service;

import com.vium.trade.entity.Trade;

class NoOpTradeChatNotifier implements TradeChatNotifier {

	@Override
	public void notify(Trade trade, TradeSystemEvent event) {
	}
}
