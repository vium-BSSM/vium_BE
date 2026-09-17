package com.vium.auth.service;

import com.vium.global.security.JwtProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
	private final JwtEncoder jwtEncoder;
	private final JwtProperties properties;
	private final Clock authClock;

	public TokenPair issue(Long userId) {
		Instant issuedAt = authClock.instant().truncatedTo(ChronoUnit.SECONDS);
		Instant refreshExpiresAt = issuedAt.plusSeconds(properties.refreshTokenSeconds());
		return new TokenPair(
			encode(userId, "access", issuedAt, issuedAt.plusSeconds(properties.accessTokenSeconds())),
			encode(userId, "refresh", issuedAt, refreshExpiresAt), issuedAt, refreshExpiresAt);
	}

	private String encode(Long userId, String type, Instant issuedAt, Instant expiresAt) {
		JwtClaimsSet claims = JwtClaimsSet.builder().issuer(properties.issuer())
			.subject(userId.toString()).issuedAt(issuedAt).expiresAt(expiresAt)
			.id(UUID.randomUUID().toString()).claim("token_type", type).build();
		return jwtEncoder.encode(JwtEncoderParameters.from(
			JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims)).getTokenValue();
	}

	public record TokenPair(String accessToken, String refreshToken, Instant issuedAt, Instant refreshExpiresAt) {
		@Override
		public String toString() { return "TokenPair[tokens=REDACTED]"; }
	}
}
