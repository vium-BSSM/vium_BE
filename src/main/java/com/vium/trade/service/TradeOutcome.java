package com.vium.trade.service;

import com.vium.trade.dto.TradeResult;

/** 만료 상태 변경을 커밋한 뒤 HTTP 오류로 변환하기 위한 결과. */
public sealed interface TradeOutcome {
	record Done(TradeResult result) implements TradeOutcome {}
	record PostExpired() implements TradeOutcome {}
}
