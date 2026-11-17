package com.vium.post.controller;

import com.vium.global.common.ApiResponse;
import com.vium.global.security.CurrentUserProvider;
import com.vium.post.dto.PostLikeResponse;
import com.vium.post.service.PostLikeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/posts/{postId}/like")
@RequiredArgsConstructor
public class PostLikeController {

	private final PostLikeService postLikeService;
	private final CurrentUserProvider currentUserProvider;

	@PutMapping
	public ApiResponse<PostLikeResponse> like(@PathVariable Long postId) {
		Long userId = currentUserProvider.getCurrentUserId();
		return ApiResponse.ok(postLikeService.like(userId, postId));
	}

	@DeleteMapping
	public ApiResponse<PostLikeResponse> unlike(@PathVariable Long postId) {
		Long userId = currentUserProvider.getCurrentUserId();
		return ApiResponse.ok(postLikeService.unlike(userId, postId));
	}
}
