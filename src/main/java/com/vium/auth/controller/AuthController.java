package com.vium.auth.controller;

import com.vium.auth.dto.LoginRequest;
import com.vium.auth.dto.LoginResponse;
import com.vium.auth.dto.LogoutRequest;
import com.vium.auth.dto.RefreshRequest;
import com.vium.auth.dto.RefreshResponse;
import com.vium.auth.dto.RegisterRequest;
import com.vium.auth.dto.RegisterResponse;
import com.vium.auth.service.AuthService;
import com.vium.global.common.ApiResponse;
import com.vium.global.security.CurrentUserProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
	private final AuthService authService;
	private final CurrentUserProvider currentUserProvider;

	@PostMapping("/logout")
	public ResponseEntity<ApiResponse<Void>> logout(@Valid @RequestBody LogoutRequest request) {
		authService.logout(currentUserProvider.getCurrentUserId(), request);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(null));
	}

	@PostMapping("/token/refresh")
	public ResponseEntity<ApiResponse<RefreshResponse>> refresh(@Valid @RequestBody RefreshRequest request) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(ApiResponse.ok(authService.refresh(request)));
	}

	@PostMapping("/register")
	public ResponseEntity<ApiResponse<RegisterResponse>> register(@Valid @RequestBody RegisterRequest request) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(ApiResponse.ok(authService.register(request)));
	}

	@PostMapping("/login")
	public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(ApiResponse.ok(authService.login(request)));
	}
}
