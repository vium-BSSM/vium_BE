package com.vium.post.service;

import com.vium.inventory.dto.IngredientDetailResponse;
import com.vium.inventory.service.InventoryService;
import com.vium.post.dto.PostIneligibleReason;
import com.vium.post.dto.PostPreviewResponse;
import com.vium.post.dto.PostPriceEstimate;
import com.vium.post.dto.SaleBlockedReason;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PostPreviewService {

	private final InventoryService inventoryService;
	private final PostEligibilityService postEligibilityService;
	private final PostPricingService postPricingService;

	@Transactional(readOnly = true)
	public PostPreviewResponse preview(Long userId, Long ingredientId) {
		IngredientDetailResponse detail = inventoryService.getDetail(userId, ingredientId);

		List<PostIneligibleReason> reasons = postEligibilityService.evaluate(detail);
		boolean eligible = reasons.isEmpty();

		boolean saleAllowed = false;
		SaleBlockedReason saleBlockedReason = null;
		PostPriceEstimate creditEstimate = null;

		if (eligible) {
			Optional<PostPriceEstimate> estimate = postPricingService.estimate(
				detail.ingredientCatalogId(), detail.unitId(), detail.remainingQuantity(), detail.expiresOn());
			if (estimate.isEmpty()) {
				saleBlockedReason = SaleBlockedReason.NO_PRICE_BASIS;
			} else if (estimate.get().recommendedPrice() <= 0) {
				saleBlockedReason = SaleBlockedReason.ZERO_PRICE;
			} else {
				saleAllowed = true;
				creditEstimate = estimate.get();
			}
		}

		return new PostPreviewResponse(detail.inventoryItemId(), eligible, reasons, saleAllowed, saleBlockedReason,
			creditEstimate);
	}
}
