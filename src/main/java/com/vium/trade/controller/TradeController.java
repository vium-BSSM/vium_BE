package com.vium.trade.controller;

import com.vium.global.common.ApiResponse;
import com.vium.global.security.CurrentUserProvider;
import com.vium.trade.dto.TradeResult;
import com.vium.trade.dto.TradeCompleteResult;
import com.vium.trade.dto.TradeCancelRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RequestBody;
import com.vium.trade.service.TradeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class TradeController {
	private final TradeService tradeService;
	private final CurrentUserProvider currentUserProvider;

	@PostMapping("/api/posts/{postId}/trades")
	public ApiResponse<TradeResult> request(@PathVariable Long postId) {
		return ApiResponse.ok(tradeService.request(currentUserProvider.getCurrentUserId(), postId));
	}

	@PostMapping("/api/me/trades/{tradeId}/accept")
	public ApiResponse<TradeResult> accept(@PathVariable Long tradeId) {
		return ApiResponse.ok(tradeService.accept(currentUserProvider.getCurrentUserId(), tradeId));
	}

	@PostMapping("/api/me/trades/{tradeId}/reject")
	public ApiResponse<TradeResult> reject(@PathVariable Long tradeId) {
		return ApiResponse.ok(tradeService.reject(currentUserProvider.getCurrentUserId(), tradeId));
	}

	@PostMapping("/api/me/trades/{tradeId}/cancel")
	public ApiResponse<TradeResult> cancel(@PathVariable Long tradeId,
			@Valid @RequestBody(required = false) TradeCancelRequest request) {
		return ApiResponse.ok(tradeService.cancel(currentUserProvider.getCurrentUserId(), tradeId,
			request == null ? null : request.reason()));
	}

	@PostMapping("/api/me/trades/{tradeId}/complete")
	public ApiResponse<TradeCompleteResult> complete(@PathVariable Long tradeId) {
		return ApiResponse.ok(tradeService.complete(currentUserProvider.getCurrentUserId(), tradeId));
	}
}
