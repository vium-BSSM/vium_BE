package com.vium.post.controller;

import com.vium.global.common.ApiResponse;
import com.vium.global.security.CurrentUserProvider;
import com.vium.post.dto.PostPreviewResponse;
import com.vium.post.service.PostPreviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me/ingredients")
@RequiredArgsConstructor
public class PostPreviewController {

	private final PostPreviewService postPreviewService;
	private final CurrentUserProvider currentUserProvider;

	@GetMapping("/{ingredientId}/post-preview")
	public ApiResponse<PostPreviewResponse> preview(@PathVariable Long ingredientId) {
		Long userId = currentUserProvider.getCurrentUserId();
		return ApiResponse.ok(postPreviewService.preview(userId, ingredientId));
	}
}
