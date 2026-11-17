package com.vium.chat.dto;

import java.util.List;

public record ChatRoomListResponse(List<ChatRoomSummaryDto> chatRooms, String nextCursor, boolean hasNext) {
}
