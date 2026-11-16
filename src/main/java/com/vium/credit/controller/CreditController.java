package com.vium.credit.controller;

import com.vium.credit.config.CreditRewardProperties;
import com.vium.credit.dto.CreditBalanceResponse;
import com.vium.credit.dto.CreditTransactionPageResponse;
import com.vium.credit.dto.DailyRewardResponse;
import com.vium.credit.entity.CreditTransactionType;
import com.vium.credit.service.CreditService;
import com.vium.global.common.ApiResponse;
import com.vium.global.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me/credits")
@RequiredArgsConstructor
@EnableConfigurationProperties(CreditRewardProperties.class)
public class CreditController {

	private final CreditService creditService;
	private final CurrentUserProvider currentUserProvider;
	private final CreditRewardProperties creditRewardProperties;

	@GetMapping
	public ApiResponse<CreditBalanceResponse> getBalance() {
		Long userId = currentUserProvider.getCurrentUserId();
		return ApiResponse.ok(
			new CreditBalanceResponse(creditService.getAvailableBalance(userId), creditService.getHeldBalance(userId)));
	}

	@GetMapping("/transactions")
	public ApiResponse<CreditTransactionPageResponse> getTransactions(
			@RequestParam(required = false) CreditTransactionType type,
			@RequestParam(required = false) Long cursor,
			@RequestParam(defaultValue = "20") int size) {
		Long userId = currentUserProvider.getCurrentUserId();
		return ApiResponse.ok(creditService.getTransactionPage(userId, type, cursor, size));
	}

	@PostMapping("/daily-reward")
	public ApiResponse<DailyRewardResponse> claimDailyReward() {
		Long userId = currentUserProvider.getCurrentUserId();
		int amount = creditRewardProperties.amount();
		long availableBalance = creditService.claimDailyReward(userId, amount);
		return ApiResponse.ok(new DailyRewardResponse(amount, availableBalance));
	}
}
