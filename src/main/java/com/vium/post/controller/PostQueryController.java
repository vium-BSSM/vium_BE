package com.vium.post.controller;

import com.vium.global.common.ApiResponse;
import com.vium.global.security.CurrentUserProvider;
import com.vium.post.dto.PostDetailResponse;
import com.vium.post.dto.PostListResponse;
import com.vium.post.dto.PostSort;
import com.vium.post.entity.PostType;
import com.vium.post.service.PostDetailService;
import com.vium.post.service.PostListService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/posts")
@RequiredArgsConstructor
public class PostQueryController {

	private final PostListService postListService;
	private final PostDetailService postDetailService;
	private final CurrentUserProvider currentUserProvider;

	@GetMapping
	public ApiResponse<PostListResponse> list(
			@RequestParam(required = false) Double latitude,
			@RequestParam(required = false) Double longitude,
			@RequestParam(required = false) Integer distance,
			@RequestParam(required = false) PostSort sort,
			@RequestParam(required = false) PostType type,
			@RequestParam(required = false) Long ingredientCatalogId,
			@RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer size) {
		Long userId = currentUserProvider.getCurrentUserId();
		return ApiResponse.ok(postListService.list(userId, latitude, longitude, distance, sort, type,
			ingredientCatalogId, cursor, size));
	}

	@GetMapping("/{postId}")
	public ApiResponse<PostDetailResponse> detail(@PathVariable Long postId,
			@RequestParam(required = false) Double latitude,
			@RequestParam(required = false) Double longitude) {
		Long userId = currentUserProvider.getCurrentUserId();
		return ApiResponse.ok(postDetailService.getDetail(userId, postId, latitude, longitude));
	}
}
