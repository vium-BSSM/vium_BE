package com.vium.chat.dto;

import com.vium.chat.entity.ChatMessageType;
import java.time.OffsetDateTime;

public record LastMessageDto(ChatMessageType type, String preview, OffsetDateTime sentAt) {
}
