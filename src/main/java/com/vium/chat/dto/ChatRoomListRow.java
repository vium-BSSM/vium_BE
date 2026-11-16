package com.vium.chat.dto;

import java.time.LocalDateTime;

/**
 * ChatRoomQueryRepository가 반환하는 방 목록 1행. activityAt은 커서 계산에만 쓰고 응답에 노출하지 않는다.
 */
public record ChatRoomListRow(ChatRoomSummaryDto summary, LocalDateTime activityAt) {
}
