package com.vium.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vium.global.security.JwtProperties;
import com.vium.global.security.TokenConfig;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;

class JwtClockSkewTest {
	@ParameterizedTest
	@CsvSource({
		"exp, -59, 60, true", "exp, -60, 60, false", "exp, -61, 60, false",
		"iat, 59, 60, true", "iat, 60, 60, true", "iat, 61, 60, false",
		"nbf, 59, 60, true", "nbf, 60, 60, true", "nbf, 61, 60, false",
		"exp, 0, 0, false", "exp, 1, 0, true", "iat, 1, 0, false", "nbf, 1, 0, false",
		"exp, -29, 30, true", "exp, -30, 30, false"
	})
	void validatesTimeClaimsAgainstConfiguredSkew(String claim, long offset, long skew, boolean accepted) {
		Instant now = Instant.parse("2026-09-17T00:00:00Z");
		String key = Base64.getEncoder().encodeToString(new byte[32]);
		var properties = new JwtProperties(key, "vium-test", 3600, 1209600, skew);
		var config = new TokenConfig();
		var decoder = config.jwtDecoder(properties, Clock.fixed(now, ZoneOffset.UTC));
		var claims = JwtClaimsSet.builder().issuer("vium-test").subject("42")
			.claim("token_type", "access")
			.issuedAt(claim.equals("iat") ? now.plusSeconds(offset) : now.minusSeconds(3600))
			.expiresAt(claim.equals("exp") ? now.plusSeconds(offset) : now.plusSeconds(3600))
			.notBefore(claim.equals("nbf") ? now.plusSeconds(offset) : now.minusSeconds(3600)).build();
		String token = config.jwtEncoder(properties).encode(JwtEncoderParameters.from(
			JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims)).getTokenValue();
		if (accepted) {
			assertThatCode(() -> decoder.decode(token)).doesNotThrowAnyException();
		} else {
			assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
		}
	}
}
