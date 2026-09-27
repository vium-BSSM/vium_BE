package com.vium.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
	@NotBlank @Email @Size(max = 255) String email,
	@NotBlank @Size(max = 72) String password,
	@NotBlank @Size(max = 80) String displayName
) {
	@Override
	public String toString() { return "RegisterRequest[credentials=REDACTED]"; }
}
