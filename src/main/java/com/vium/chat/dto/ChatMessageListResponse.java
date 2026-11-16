package com.vium.chat.dto;

import java.util.List;

public record ChatMessageListResponse(List<ChatMessageDto> messages, String nextCursor, boolean hasNext) {
}
