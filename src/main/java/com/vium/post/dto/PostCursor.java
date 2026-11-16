package com.vium.post.dto;

import java.time.OffsetDateTime;

/**
 * 목록 커서. DISTANCE 정렬이면 distanceMeters, LATEST 정렬이면 createdAt만 채워진다.
 */
public record PostCursor(Integer distanceMeters, OffsetDateTime createdAt, Long postId) {
}
