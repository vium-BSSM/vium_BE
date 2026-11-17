package com.vium.safety.dto;

import java.time.LocalDateTime;

public record BlockedUserRow(Long userId, String displayName, LocalDateTime blockedAt) {
}
