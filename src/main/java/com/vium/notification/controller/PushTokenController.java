package com.vium.notification.controller;

import com.vium.global.common.ApiResponse;
import com.vium.global.security.CurrentUserProvider;
import com.vium.notification.dto.PushTokenRegisterRequest;
import com.vium.notification.dto.PushTokenRegisterResponse;
import com.vium.notification.dto.PushTokenUnregisterRequest;
import com.vium.notification.service.PushTokenService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me/push-tokens")
@RequiredArgsConstructor
public class PushTokenController {

	private final PushTokenService pushTokenService;
	private final CurrentUserProvider currentUser;

	@PutMapping
	public ApiResponse<PushTokenRegisterResponse> register(@Valid @RequestBody PushTokenRegisterRequest request) {
		Long pushTokenId = pushTokenService.register(
			currentUser.getCurrentUserId(), request.token(), request.platform());
		return ApiResponse.ok(new PushTokenRegisterResponse(pushTokenId));
	}

	@PostMapping("/unregister")
	public ApiResponse<Void> unregister(@Valid @RequestBody PushTokenUnregisterRequest request) {
		pushTokenService.unregister(currentUser.getCurrentUserId(), request.token());
		return ApiResponse.ok();
	}
}
