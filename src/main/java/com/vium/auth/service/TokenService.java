package com.vium.auth.service;

import com.vium.global.security.JwtProperties;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TokenService {
	private final SecureRandom secureRandom = new SecureRandom();
	private final JwtEncoder jwtEncoder;
	private final JwtProperties properties;
	private final Clock authClock;

	public TokenPair issue(Long userId) {
		Instant issuedAt = authClock.instant().truncatedTo(ChronoUnit.SECONDS);
		Instant refreshExpiresAt = issuedAt.plusSeconds(properties.refreshTokenSeconds());
		return new TokenPair(
			encodeAccessToken(userId, issuedAt, issuedAt.plusSeconds(properties.accessTokenSeconds())),
			generateRefreshToken(), issuedAt, refreshExpiresAt);
	}

	private String encodeAccessToken(Long userId, Instant issuedAt, Instant expiresAt) {
		JwtClaimsSet claims = JwtClaimsSet.builder().issuer(properties.issuer())
			.subject(userId.toString()).issuedAt(issuedAt).expiresAt(expiresAt)
			.id(UUID.randomUUID().toString()).claim("token_type", "access").build();
		return jwtEncoder.encode(JwtEncoderParameters.from(
			JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims)).getTokenValue();
	}

	private String generateRefreshToken() {
		byte[] bytes = new byte[32];
		secureRandom.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	public record TokenPair(String accessToken, String refreshToken, Instant issuedAt, Instant refreshExpiresAt) {
		@Override
		public String toString() { return "TokenPair[tokens=REDACTED]"; }
	}
}
