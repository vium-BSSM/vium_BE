package com.vium.credit.dto;

import com.vium.credit.entity.CreditTransactionType;
import java.time.OffsetDateTime;

public record CreditTransactionItem(
		Long transactionId, CreditTransactionType type, int amount, Long tradeId, OffsetDateTime createdAt) {
}
