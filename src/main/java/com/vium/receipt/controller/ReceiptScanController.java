package com.vium.receipt.controller;

import com.vium.global.common.ApiResponse;
import com.vium.global.security.CurrentUserProvider;
import com.vium.receipt.dto.PurchaseDetailResponse;
import com.vium.receipt.dto.ScanRequest;
import com.vium.receipt.entity.PurchaseType;
import com.vium.receipt.service.PurchaseScanService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ReceiptScanController {
	private final PurchaseScanService scans;
	private final CurrentUserProvider currentUser;

	@PostMapping("/api/me/receipts/scan")
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<PurchaseDetailResponse> receipt(@Valid @RequestBody ScanRequest request) {
		return ApiResponse.ok(scans.scan(currentUser.getCurrentUserId(), PurchaseType.RECEIPT, request));
	}

	@PostMapping("/api/me/online-orders/scan")
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<PurchaseDetailResponse> onlineOrder(@Valid @RequestBody ScanRequest request) {
		return ApiResponse.ok(scans.scan(currentUser.getCurrentUserId(), PurchaseType.ONLINE_ORDER, request));
	}
}
