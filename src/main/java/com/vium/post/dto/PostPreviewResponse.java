package com.vium.post.dto;

import java.util.List;

public record PostPreviewResponse(
	Long inventoryItemId,
	boolean eligible,
	List<PostIneligibleReason> ineligibleReasons,
	boolean saleAllowed,
	SaleBlockedReason saleBlockedReason,
	PostPriceEstimate creditEstimate
) {
}
