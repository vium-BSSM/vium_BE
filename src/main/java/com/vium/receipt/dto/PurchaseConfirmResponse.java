package com.vium.receipt.dto;

import com.vium.inventory.dto.IngredientResponse;
import com.vium.receipt.entity.PurchaseStatus;
import java.util.List;

public record PurchaseConfirmResponse(Long purchaseId, PurchaseStatus status, List<IngredientResponse> ingredients) {
	public PurchaseConfirmResponse { ingredients = List.copyOf(ingredients); }
}
