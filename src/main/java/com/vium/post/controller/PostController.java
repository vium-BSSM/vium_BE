package com.vium.post.controller;

import tools.jackson.databind.JsonNode;
import com.vium.global.common.ApiResponse;
import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.global.security.CurrentUserProvider;
import com.vium.post.dto.PostCreateRequest;
import com.vium.post.dto.PostCreateResponse;
import com.vium.post.dto.PostDetailResponse;
import com.vium.post.dto.PostUpdateCommand;
import com.vium.post.service.PostDeleteService;
import com.vium.post.service.PostRegisterService;
import com.vium.post.service.PostUpdateService;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me/posts")
@RequiredArgsConstructor
public class PostController {

	private final PostRegisterService postRegisterService;
	private final PostUpdateService postUpdateService;
	private final PostDeleteService postDeleteService;
	private final CurrentUserProvider currentUserProvider;

	@PostMapping
	public ApiResponse<PostCreateResponse> register(@Valid @RequestBody PostCreateRequest request) {
		Long userId = currentUserProvider.getCurrentUserId();
		return ApiResponse.ok(postRegisterService.register(userId, request));
	}

	@PatchMapping("/{postId}")
	public ApiResponse<PostDetailResponse> update(@PathVariable Long postId, @RequestBody JsonNode body) {
		Long userId = currentUserProvider.getCurrentUserId();
		PostUpdateCommand command = parseUpdateCommand(body);
		return ApiResponse.ok(postUpdateService.update(userId, postId, command));
	}

	@DeleteMapping("/{postId}")
	public ApiResponse<Void> delete(@PathVariable Long postId) {
		Long userId = currentUserProvider.getCurrentUserId();
		postDeleteService.delete(userId, postId);
		return ApiResponse.ok();
	}

	private PostUpdateCommand parseUpdateCommand(JsonNode body) {
		return new PostUpdateCommand(
			optionalText(body, "title"),
			optionalText(body, "description"),
			optionalInt(body, "price"),
			optionalLongList(body, "imageIds"));
	}

	private Optional<String> optionalText(JsonNode body, String field) {
		if (!body.has(field)) {
			return Optional.empty();
		}
		JsonNode node = body.get(field);
		requireNotJsonNull(node, field);
		if (!node.isString()) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, field + " 형식이 올바르지 않습니다");
		}
		return Optional.of(node.asString());
	}

	private Optional<Integer> optionalInt(JsonNode body, String field) {
		if (!body.has(field)) {
			return Optional.empty();
		}
		JsonNode node = body.get(field);
		requireNotJsonNull(node, field);
		if (!node.isIntegralNumber()) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, field + " 형식이 올바르지 않습니다");
		}
		return Optional.of(node.intValue());
	}

	private Optional<List<Long>> optionalLongList(JsonNode body, String field) {
		if (!body.has(field)) {
			return Optional.empty();
		}
		JsonNode node = body.get(field);
		requireNotJsonNull(node, field);
		if (!node.isArray()) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, field + " 형식이 올바르지 않습니다");
		}
		List<Long> values = new ArrayList<>();
		for (JsonNode item : node) {
			if (!item.isIntegralNumber()) {
				throw new BusinessException(ErrorCode.INVALID_REQUEST, field + " 형식이 올바르지 않습니다");
			}
			values.add(item.longValue());
		}
		return Optional.of(values);
	}

	private void requireNotJsonNull(JsonNode node, String field) {
		if (node == null || node.isNull()) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, field + "은(는) null일 수 없습니다");
		}
	}
}
