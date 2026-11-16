package com.vium.safety.dto;

import java.util.List;

public record BlockedUsersResponse(List<BlockedUserItem> blockedUsers) {
}
