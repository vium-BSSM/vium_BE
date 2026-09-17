package com.vium.global.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Clock;
import java.util.Base64;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class TokenConfig {
	@Bean
	public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

	@Bean
	public Clock authClock() { return Clock.systemUTC(); }

	@Bean
	public JwtEncoder jwtEncoder(JwtProperties properties) {
		byte[] secret;
		try {
			secret = Base64.getDecoder().decode(properties.secret());
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("JWT_SECRET must be Base64 encoded");
		}
		if (secret.length < 32) {
			throw new IllegalArgumentException("JWT_SECRET must decode to at least 32 bytes");
		}
		return new NimbusJwtEncoder(new ImmutableSecret<>(secret));
	}
}
