package com.vium.chat.controller;

import com.vium.chat.dto.CreateChatRoomResponse;
import com.vium.chat.service.ChatRoomService;
import com.vium.global.common.ApiResponse;
import com.vium.global.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/posts/{postId}/chat-rooms")
@RequiredArgsConstructor
public class ChatRoomCreateController {

	private final ChatRoomService chatRoomService;
	private final CurrentUserProvider currentUserProvider;

	@PostMapping
	public ApiResponse<CreateChatRoomResponse> create(@PathVariable Long postId) {
		Long userId = currentUserProvider.getCurrentUserId();
		return ApiResponse.ok(chatRoomService.create(userId, postId));
	}
}
