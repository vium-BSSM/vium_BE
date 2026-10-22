package com.vium.receipt.controller;

import com.vium.global.common.ApiResponse;
import com.vium.global.security.CurrentUserProvider;
import com.vium.receipt.dto.*;
import com.vium.receipt.entity.PurchaseStatus;
import com.vium.receipt.entity.PurchaseType;
import com.vium.receipt.service.PurchaseConfirmService;
import com.vium.receipt.service.PurchaseQueryService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/me/purchases")
@RequiredArgsConstructor
public class PurchaseController {
	private final PurchaseConfirmService confirms;
	private final PurchaseQueryService queries;
	private final CurrentUserProvider currentUser;

	@PostMapping("/{purchaseId}/confirm")
	public ApiResponse<PurchaseConfirmResponse> confirm(@PathVariable Long purchaseId,
			@Valid @RequestBody PurchaseConfirmRequest request) {
		return ApiResponse.ok(confirms.confirm(currentUser.getCurrentUserId(), purchaseId, request));
	}

	@GetMapping
	public ApiResponse<PurchaseListResponse> list(@RequestParam(required = false) PurchaseType type,
			@RequestParam(required = false) PurchaseStatus status,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return ApiResponse.ok(queries.list(currentUser.getCurrentUserId(), type, status, from, to));
	}

	@GetMapping("/{purchaseId}")
	public ApiResponse<PurchaseDetailResponse> detail(@PathVariable Long purchaseId) {
		return ApiResponse.ok(queries.detail(currentUser.getCurrentUserId(), purchaseId));
	}

	@DeleteMapping("/{purchaseId}")
	public ApiResponse<Void> delete(@PathVariable Long purchaseId) {
		queries.delete(currentUser.getCurrentUserId(), purchaseId);
		return ApiResponse.ok();
	}
}
