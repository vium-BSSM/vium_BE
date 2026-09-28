package com.vium.auth.dto;

import com.vium.user.dto.UserIdentity;

public record LoginResponse(String accessToken, String refreshToken, UserIdentity user) {
	@Override
	public String toString() { return "LoginResponse[tokens=REDACTED]"; }
}
