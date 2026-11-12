package com.vium.user.controller;

import com.vium.global.common.ApiResponse;
import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.global.security.CurrentUserProvider;
import com.vium.image.service.ImageService;
import com.vium.user.dto.ProfileUpdateRequest;
import com.vium.user.dto.UserProfileResponse;
import com.vium.user.dto.UserProfileRow;
import com.vium.user.service.AccountDeletionService;
import com.vium.user.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class UserController {
	private final UserProfileService userProfileService;
	private final AccountDeletionService accountDeletionService;
	private final ImageService imageService;
	private final CurrentUserProvider currentUserProvider;

	@GetMapping
	public ResponseEntity<ApiResponse<UserProfileResponse>> getProfile() {
		var row = userProfileService.getProfileView(currentUserProvider.getCurrentUserId());
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(ApiResponse.ok(toResponse(row)));
	}

	@PatchMapping
	public ResponseEntity<ApiResponse<UserProfileResponse>> updateProfile(@RequestBody ProfileUpdateRequest request) {
		if (request.displayName() == null && request.profileImageId() == null) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, "수정할 필드가 없습니다");
		}
		Long userId = currentUserProvider.getCurrentUserId();
		if (request.profileImageId() != null) {
			imageService.requireProfileImage(userId, request.profileImageId());
		}
		var row = userProfileService.updateProfile(userId, request.displayName(), request.profileImageId());
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(ApiResponse.ok(toResponse(row)));
	}

	@DeleteMapping
	public ResponseEntity<ApiResponse<Void>> deleteAccount() {
		accountDeletionService.deleteAccount(currentUserProvider.getCurrentUserId());
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(null));
	}

	private UserProfileResponse toResponse(UserProfileRow row) {
		String profileImageUrl = imageService.findProfileImageUrl(row.profileImageId());
		return new UserProfileResponse(row.id(), row.email(), row.displayName(), profileImageUrl);
	}
}
