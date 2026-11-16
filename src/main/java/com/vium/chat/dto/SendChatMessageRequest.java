package com.vium.chat.dto;

import com.vium.chat.entity.ChatMessageType;

public record SendChatMessageRequest(ChatMessageType type, String content, Long imageId) {
}
