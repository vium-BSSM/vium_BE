package com.vium.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PushTokenUnregisterRequest(
	@NotBlank @Size(max = 512) String token
) {
}
