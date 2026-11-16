package com.vium.trade.controller;

import com.vium.global.common.ApiResponse;
import com.vium.global.security.CurrentUserProvider;
import com.vium.trade.dto.TradeListResponse;
import com.vium.trade.dto.TradeRole;
import com.vium.trade.entity.TradeStatus;
import com.vium.trade.service.TradeQueryService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class TradeQueryController {
	private final TradeQueryService queryService;
	private final CurrentUserProvider currentUserProvider;

	@GetMapping("/api/me/trades")
	public ApiResponse<TradeListResponse> list(@RequestParam(required = false) TradeRole role,
			@RequestParam(required = false) List<TradeStatus> status, @RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "20") int size) {
		return ApiResponse.ok(queryService.list(currentUserProvider.getCurrentUserId(), role, status, cursor, size));
	}
}
