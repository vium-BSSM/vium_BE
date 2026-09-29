package com.vium.user;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.auth.service.TokenService;
import com.vium.global.security.JwtProperties;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:profile-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Sql(scripts = "/db/auth-users.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class UserProfileIntegrationTest {
	@Autowired private MockMvc mockMvc;
	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private TokenService tokenService;
	@Autowired private JwtProperties jwtProperties;
	@MockitoBean private Clock authClock;

	private static final Instant NOW = Instant.parse("2026-09-30T00:00:00Z");
	private TokenService.TokenPair tokens;

	@BeforeEach
	void setUp() {
		when(authClock.instant()).thenReturn(NOW);
		jdbcTemplate.update("delete from user_sessions");
		jdbcTemplate.update("delete from users");
		jdbcTemplate.update("""
			insert into users (id,email,display_name,password_hash,created_at,updated_at)
			values (42,'profile@example.com','Profile User','secret-hash',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
			       (43,'other@example.com','Other User',null,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
			""");
		tokens = tokenService.issue(42L);
	}

	@Test
	void returnsOnlyCurrentUserIdentityWithoutCaching() throws Exception {
		profile(tokens.accessToken()).andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$", aMapWithSize(3)))
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data", aMapWithSize(3)))
			.andExpect(jsonPath("$.data.id").value(42))
			.andExpect(jsonPath("$.data.email").value("profile@example.com"))
			.andExpect(jsonPath("$.data.displayName").value("Profile User"))
			.andExpect(jsonPath("$.error").value(nullValue()));
	}

	@Test
	void returnsIdentityForUserWithoutPassword() throws Exception {
		profile(tokenService.issue(43L).accessToken()).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.id").value(43))
			.andExpect(jsonPath("$.data.email").value("other@example.com"))
			.andExpect(jsonPath("$.data.displayName").value("Other User"));
	}

	@Test
	void queryParameterCannotOverrideAuthenticatedUser() throws Exception {
		mockMvc.perform(get("/api/me").param("userId", "43")
				.header("Authorization", "Bearer " + tokens.accessToken()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(42));
	}

	@Test
	void returnsLatestDatabaseValuesAfterTokenIssuance() throws Exception {
		jdbcTemplate.update("update users set display_name = ?, email = ? where id = 42",
			"Updated Name", "updated@example.com");
		profile(tokens.accessToken()).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.email").value("updated@example.com"))
			.andExpect(jsonPath("$.data.displayName").value("Updated Name"));
	}

	@Test
	void rejectsMissingUser() throws Exception {
		assertUnauthorized(profile(tokenService.issue(999L).accessToken()));
	}

	@Test
	void rejectsUserDeletedAfterTokenIssuance() throws Exception {
		jdbcTemplate.update("update users set deleted_at = CURRENT_TIMESTAMP where id = 42");
		assertUnauthorized(profile(tokens.accessToken()));
	}

	@ParameterizedTest
	@ValueSource(strings = {"missing", "malformed", "expired", "refresh"})
	void requiresValidAccessToken(String kind) throws Exception {
		var request = get("/api/me");
		if (kind.equals("malformed")) {
			request.header("Authorization", "Bearer invalid-token");
		} else if (kind.equals("expired")) {
			when(authClock.instant()).thenReturn(NOW.plusSeconds(
				jwtProperties.accessTokenSeconds() + jwtProperties.clockSkewSeconds() + 1));
			request.header("Authorization", "Bearer " + tokens.accessToken());
		} else if (kind.equals("refresh")) {
			request.header("Authorization", "Bearer " + tokens.refreshToken());
		}
		assertUnauthorized(mockMvc.perform(request));
	}

	private ResultActions profile(String token) throws Exception {
		return mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + token));
	}

	private void assertUnauthorized(ResultActions result) throws Exception {
		result.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").value(nullValue()))
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
	}
}
