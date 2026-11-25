package com.vium.ingredient.controller;

import com.vium.global.common.ApiResponse;
import com.vium.global.exception.InvalidRequestException;
import com.vium.ingredient.dto.ExpiryEstimateResponse;
import com.vium.ingredient.service.ExpiryEstimationService;
import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ExpiryEstimationController {

	private final ExpiryEstimationService expiryEstimationService;
	private final Clock clock;

	@GetMapping("/api/ingredients/estimate-expiry")
	public ApiResponse<ExpiryEstimateResponse> estimate(
			@RequestParam Long ingredientCatalogId,
			@RequestParam(required = false) Short storageMethodId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate purchasedOn) {
		LocalDate today = LocalDate.now(clock);
		LocalDate effectivePurchaseDate = purchasedOn == null ? today : purchasedOn;
		if (effectivePurchaseDate.isAfter(today)) {
			throw new InvalidRequestException("purchasedOn은 미래일 수 없습니다");
		}
		return ApiResponse.ok(expiryEstimationService.preview(ingredientCatalogId, storageMethodId, effectivePurchaseDate));
	}
}
