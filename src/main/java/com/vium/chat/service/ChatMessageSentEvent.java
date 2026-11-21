package com.vium.chat.service;

public record ChatMessageSentEvent(Long chatRoomId, Long recipientUserId) {
}
