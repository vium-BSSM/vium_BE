package com.vium.chat.controller;

import com.vium.chat.dto.ChatRoomListResponse;
import com.vium.chat.service.ChatRoomService;
import com.vium.global.common.ApiResponse;
import com.vium.global.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me/chat-rooms")
@RequiredArgsConstructor
public class ChatRoomController {

	private final ChatRoomService chatRoomService;
	private final CurrentUserProvider currentUserProvider;

	@GetMapping
	public ApiResponse<ChatRoomListResponse> list(
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer size) {
		Long userId = currentUserProvider.getCurrentUserId();
		return ApiResponse.ok(chatRoomService.list(userId, cursor, size));
	}

	@DeleteMapping("/{chatRoomId}")
	public ApiResponse<Void> leave(@PathVariable Long chatRoomId) {
		Long userId = currentUserProvider.getCurrentUserId();
		chatRoomService.leave(userId, chatRoomId);
		return ApiResponse.ok();
	}
}
