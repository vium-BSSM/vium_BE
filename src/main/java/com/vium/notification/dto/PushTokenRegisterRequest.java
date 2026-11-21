package com.vium.notification.dto;

import com.vium.notification.entity.PushPlatform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PushTokenRegisterRequest(
	@NotBlank @Size(max = 512) String token,
	@NotNull PushPlatform platform
) {
}
