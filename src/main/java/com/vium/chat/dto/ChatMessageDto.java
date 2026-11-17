package com.vium.chat.dto;

import com.vium.chat.entity.ChatMessageType;
import java.time.OffsetDateTime;

public record ChatMessageDto(
		Long messageId,
		Long chatRoomId,
		Long senderId,
		ChatMessageType type,
		String content,
		String imageUrl,
		String systemEvent,
		OffsetDateTime sentAt,
		boolean isMine) {
}
