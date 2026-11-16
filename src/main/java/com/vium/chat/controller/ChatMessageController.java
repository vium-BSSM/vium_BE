package com.vium.chat.controller;

import com.vium.chat.dto.ChatMessageDto;
import com.vium.chat.dto.ChatMessageListResponse;
import com.vium.chat.dto.SendChatMessageRequest;
import com.vium.chat.service.ChatMessageService;
import com.vium.global.common.ApiResponse;
import com.vium.global.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me/chat-rooms/{chatRoomId}/messages")
@RequiredArgsConstructor
public class ChatMessageController {

	private final ChatMessageService chatMessageService;
	private final CurrentUserProvider currentUserProvider;

	@GetMapping
	public ApiResponse<ChatMessageListResponse> history(@PathVariable Long chatRoomId,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer size) {
		Long userId = currentUserProvider.getCurrentUserId();
		return ApiResponse.ok(chatMessageService.getHistory(userId, chatRoomId, cursor, size));
	}

	@PostMapping
	public ApiResponse<ChatMessageDto> send(
			@PathVariable Long chatRoomId, @RequestBody SendChatMessageRequest request) {
		Long userId = currentUserProvider.getCurrentUserId();
		return ApiResponse.ok(chatMessageService.send(userId, chatRoomId, request));
	}
}
