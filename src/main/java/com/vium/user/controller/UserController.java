package com.vium.user.controller;

import com.vium.global.common.ApiResponse;
import com.vium.global.security.CurrentUserProvider;
import com.vium.user.dto.UserIdentity;
import com.vium.user.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class UserController {
	private final UserProfileService userProfileService;
	private final CurrentUserProvider currentUserProvider;

	@GetMapping
	public ResponseEntity<ApiResponse<UserIdentity>> getProfile() {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(ApiResponse.ok(userProfileService.getProfile(currentUserProvider.getCurrentUserId())));
	}
}
