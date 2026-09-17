package com.vium.global.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Clock;
import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class TokenConfig {
	@Bean
	public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

	@Bean
	public Clock authClock() { return Clock.systemUTC(); }

	@Bean
	public JwtDecoder jwtDecoder(JwtProperties properties, Clock authClock) {
		var decoder = NimbusJwtDecoder
			.withSecretKey(new SecretKeySpec(decodeSecret(properties), "HmacSHA256"))
			.macAlgorithm(MacAlgorithm.HS256).build();
		decoder.setJwtValidator(jwt -> {
			var now = authClock.instant();
			boolean validSubject;
			try { validSubject = Long.parseLong(jwt.getSubject()) > 0; }
			catch (NumberFormatException e) { validSubject = false; }
			if (!properties.issuer().equals(jwt.getClaimAsString("iss"))
				|| !"access".equals(jwt.getClaimAsString("token_type")) || !validSubject
				|| jwt.getExpiresAt() == null || !now.isBefore(jwt.getExpiresAt())
				|| jwt.getIssuedAt() == null || jwt.getIssuedAt().isAfter(now)
				|| (jwt.getNotBefore() != null && now.isBefore(jwt.getNotBefore()))) {
				return OAuth2TokenValidatorResult.failure(
					new OAuth2Error("invalid_token"));
			}
			return OAuth2TokenValidatorResult.success();
		});
		return decoder;
	}

	@Bean
	public JwtEncoder jwtEncoder(JwtProperties properties) {
		return new NimbusJwtEncoder(new ImmutableSecret<>(decodeSecret(properties)));
	}

	private byte[] decodeSecret(JwtProperties properties) {
		byte[] secret;
		try {
			secret = Base64.getDecoder().decode(properties.secret());
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("JWT_SECRET must be Base64 encoded");
		}
		if (secret.length < 32) {
			throw new IllegalArgumentException("JWT_SECRET must decode to at least 32 bytes");
		}
		return secret;
	}
}
