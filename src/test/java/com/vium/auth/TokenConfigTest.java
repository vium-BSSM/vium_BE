package com.vium.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vium.global.security.JwtProperties;
import com.vium.global.security.TokenConfig;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TokenConfigTest {
	@ParameterizedTest
	@ValueSource(strings = {"not base64!", "c2hvcnQ=", ""})
	void rejectsInvalidSigningKeys(String secret) {
		assertThatThrownBy(() -> new TokenConfig().jwtEncoder(new JwtProperties(secret,"vium",3600,1209600,60)))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
