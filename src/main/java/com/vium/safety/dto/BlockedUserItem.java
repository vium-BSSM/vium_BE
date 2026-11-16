package com.vium.safety.dto;

import java.time.OffsetDateTime;

public record BlockedUserItem(Long userId, String displayName, OffsetDateTime blockedAt) {
}
