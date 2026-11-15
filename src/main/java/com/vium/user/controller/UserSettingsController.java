package com.vium.user.controller;

import com.vium.global.common.ApiResponse;
import com.vium.global.security.CurrentUserProvider;
import com.vium.user.dto.UserSettingsResponse;
import com.vium.user.dto.UserSettingsUpdateRequest;
import com.vium.user.service.UserSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me/settings")
@RequiredArgsConstructor
public class UserSettingsController {
	private final UserSettingsService userSettingsService;
	private final CurrentUserProvider currentUserProvider;

	@GetMapping
	public ResponseEntity<ApiResponse<UserSettingsResponse>> getSettings() {
		var settings = userSettingsService.getSettings(currentUserProvider.getCurrentUserId());
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(settings));
	}

	@PatchMapping
	public ResponseEntity<ApiResponse<UserSettingsResponse>> updateSettings(
			@RequestBody UserSettingsUpdateRequest request) {
		var settings = userSettingsService.updateSettings(currentUserProvider.getCurrentUserId(), request);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(settings));
	}
}
