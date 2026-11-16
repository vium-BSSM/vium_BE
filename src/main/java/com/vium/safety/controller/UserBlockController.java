package com.vium.safety.controller;

import com.vium.global.common.ApiResponse;
import com.vium.global.security.CurrentUserProvider;
import com.vium.safety.dto.BlockResponse;
import com.vium.safety.dto.BlockedUsersResponse;
import com.vium.safety.service.UserBlockService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me/blocks")
@RequiredArgsConstructor
public class UserBlockController {

	private final UserBlockService userBlockService;
	private final CurrentUserProvider currentUserProvider;

	@GetMapping
	public ApiResponse<BlockedUsersResponse> listBlocks() {
		return ApiResponse.ok(userBlockService.listBlockedUsers(currentUserProvider.getCurrentUserId()));
	}

	@PutMapping("/{userId}")
	public ApiResponse<BlockResponse> block(@PathVariable Long userId) {
		userBlockService.block(currentUserProvider.getCurrentUserId(), userId);
		return ApiResponse.ok(new BlockResponse(userId, true));
	}

	@DeleteMapping("/{userId}")
	public ApiResponse<BlockResponse> unblock(@PathVariable Long userId) {
		userBlockService.unblock(currentUserProvider.getCurrentUserId(), userId);
		return ApiResponse.ok(new BlockResponse(userId, false));
	}
}
