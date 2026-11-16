package com.vium.trade.dto;

import java.time.OffsetDateTime;

public record TradeCursor(OffsetDateTime requestedAt, Long tradeId) {}
