package com.vium.chat.service;

public record ChatMessageSseEvent(Long chatRoomId, Long recipientUserId, Long messageId) {
}
