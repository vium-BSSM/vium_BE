package com.vium.trade.dto;

import jakarta.validation.constraints.Size;

public record TradeCancelRequest(@Size(max = 200) String reason) {}
