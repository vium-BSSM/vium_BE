package com.vium.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RefreshRequest(@NotBlank @Size(max = 512) String refreshToken) {
	@Override
	public String toString() { return "RefreshRequest[token=REDACTED]"; }
}
