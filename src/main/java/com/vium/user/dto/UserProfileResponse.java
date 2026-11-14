package com.vium.user.dto;

public record UserProfileResponse(Long id, String email, String displayName, String profileImageUrl) {
}
