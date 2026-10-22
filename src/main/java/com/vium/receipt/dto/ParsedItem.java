package com.vium.receipt.dto;

import java.math.BigDecimal;

/** 정규화·카탈로그 매칭 전 파서 결과. */
public record ParsedItem(String rawName, BigDecimal quantity, Short unitId,
		BigDecimal unitPrice, BigDecimal amount) {
}
