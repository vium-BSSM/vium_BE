package com.vium.auth.dto;

public record RefreshResponse(String accessToken, String refreshToken, long expiresIn) {
	@Override
	public String toString() { return "RefreshResponse[tokens=REDACTED]"; }
}
