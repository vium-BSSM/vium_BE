package com.vium.trade.controller;

import com.vium.global.common.ApiResponse;
import com.vium.global.security.CurrentUserProvider;
import com.vium.trade.dto.ReviewCreateRequest;
import com.vium.trade.dto.ReviewCreateResponse;
import com.vium.trade.dto.UserReviewsResponse;
import com.vium.trade.service.TradeReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class UserReviewController {
	private final TradeReviewService reviewService;
	private final CurrentUserProvider currentUserProvider;

	@PostMapping("/api/me/trades/{tradeId}/review")
	public ApiResponse<ReviewCreateResponse> create(@PathVariable Long tradeId,
			@Valid @RequestBody ReviewCreateRequest request) {
		return ApiResponse.ok(reviewService.create(currentUserProvider.getCurrentUserId(), tradeId, request));
	}

	@GetMapping("/api/users/{userId}/reviews")
	public ApiResponse<UserReviewsResponse> list(@PathVariable Long userId,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int size) {
		return ApiResponse.ok(reviewService.list(userId, cursor, size));
	}
}
