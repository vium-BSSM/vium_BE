package com.vium.trade.controller;

import com.vium.global.common.ApiResponse;
import com.vium.global.security.CurrentUserProvider;
import com.vium.trade.dto.TradeResult;
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
}
