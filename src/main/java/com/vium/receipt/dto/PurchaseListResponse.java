package com.vium.receipt.dto;

import java.util.List;

public record PurchaseListResponse(List<PurchaseSummaryResponse> purchases) {
	public PurchaseListResponse { purchases = List.copyOf(purchases); }
}
