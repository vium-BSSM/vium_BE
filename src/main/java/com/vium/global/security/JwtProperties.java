package com.vium.global.security;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "security.jwt")
public record JwtProperties(
	@NotBlank String secret,
	@NotBlank String issuer,
	@Min(1) long accessTokenSeconds,
	@Min(1) long refreshTokenSeconds
) {
	@Override
	public String toString() { return "JwtProperties[secret=REDACTED]"; }
}
