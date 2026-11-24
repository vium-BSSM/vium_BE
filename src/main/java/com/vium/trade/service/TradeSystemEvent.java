package com.vium.trade.service;

public enum TradeSystemEvent {
	TRADE_REQUESTED("거래 요청이 도착했어요."),
	TRADE_ACCEPTED("거래가 수락되었어요."),
	TRADE_REJECTED("거래가 거절되었어요."),
	TRADE_CANCELED("거래가 취소되었어요."),
	TRADE_COMPLETED("거래가 완료되었어요."),
	TRADE_AUTO_COMPLETE_SOON("24시간 뒤 거래가 자동으로 완료돼요.");

	private final String message;

	TradeSystemEvent(String message) {
		this.message = message;
	}

	public String message() {
		return message;
	}
}
