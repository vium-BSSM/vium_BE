package com.vium.credit.dto;

import java.util.List;

public record CreditTransactionPageResponse(
		List<CreditTransactionItem> transactions, Long nextCursor, boolean hasNext) {
}
