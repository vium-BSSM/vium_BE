package com.vium.chat.dto;

public record ChatRoomSummaryDto(
		Long chatRoomId, PostSummaryDto post, PartnerDto partner, LastMessageDto lastMessage, int unreadCount) {
}
