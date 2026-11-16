package com.vium.trade.dto;

import java.util.List;

public record TradeListResponse(List<TradeSummary> trades, String nextCursor, boolean hasNext) {}
