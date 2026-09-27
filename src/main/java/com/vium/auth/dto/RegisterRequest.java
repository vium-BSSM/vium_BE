package com.vium.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Locale;

public record RegisterRequest(
	@NotBlank @Email @Size(max = 255) String email,
	@NotBlank @Size(min = 8, max = 72) String password,
	@NotBlank @Size(max = 80) String displayName
) {
	public RegisterRequest {
		email = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
		displayName = displayName == null ? null : displayName.strip();
	}

	@Override
	public String toString() { return "RegisterRequest[credentials=REDACTED]"; }
}
