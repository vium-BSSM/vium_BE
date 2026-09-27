package com.vium.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.auth.dto.RefreshRequest;
import com.vium.auth.dto.RefreshResponse;
import com.vium.auth.entity.UserSession;
import com.vium.auth.repository.UserSessionRepository;
import com.vium.auth.service.TokenService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:refresh-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Sql(scripts = "/db/auth-users.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class RefreshIntegrationTest {
	@Autowired private MockMvc mockMvc;
	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private TokenService tokenService;
	@Autowired private PasswordEncoder passwordEncoder;
	@Autowired private JwtDecoder jwtDecoder;
	@Autowired private JsonMapper mapper;
	@MockitoBean private Clock authClock;
	@MockitoSpyBean private UserSessionRepository userSessionRepository;

	private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");
	private static final String PASSWORD = "refresh-test-password";
	private String refreshToken;
	private Long sessionId;

	@BeforeEach
	void setUp() {
		when(authClock.instant()).thenReturn(NOW);
		jdbcTemplate.update("delete from user_sessions");
		jdbcTemplate.update("delete from users");
		jdbcTemplate.update("""
			insert into users (id,email,display_name,password_hash,created_at,updated_at)
			values (42,'refresh@example.com','사용자',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
			""", passwordEncoder.encode(PASSWORD));
		refreshToken = tokenService.issue(42L).refreshToken();
		sessionId = userSessionRepository.saveAndFlush(UserSession.create(42L, refreshToken,
			NOW.minusSeconds(10), NOW.plusSeconds(3600))).getId();
	}

	@Test
	void rotatesTokensWithoutBearerAndRejectsOldToken() throws Exception {
		var result = refresh(refreshToken).andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.expiresIn").value(3600))
			.andReturn();
		var data = mapper.readTree(result.getResponse().getContentAsString()).get("data");
		String access = data.get("accessToken").asText();
		String next = data.get("refreshToken").asText();
		var jwt = jwtDecoder.decode(access);
		assertThat(jwt.getSubject()).isEqualTo("42");
		assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plusSeconds(3600));
		assertThat(next).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(refreshToken);
		assertThat(userSessionRepository.findById(sessionId).orElseThrow().getRevokedAt())
			.isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
		assertThat(userSessionRepository.findAll()).filteredOn(s -> !s.getId().equals(sessionId))
			.singleElement().satisfies(s -> {
				assertThat(s.getRefreshTokenHash()).isEqualTo(UserSession.hashToken(next)).isNotEqualTo(next);
				assertThat(s.getRevokedAt()).isNull();
				assertThat(s.getExpiresAt()).isEqualTo(LocalDateTime.ofInstant(NOW.plusSeconds(1209600), ZoneOffset.UTC));
			});
		refresh(refreshToken).andExpect(status().isUnauthorized());
		assertThat(userSessionRepository.count()).isEqualTo(2);
		refresh(next).andExpect(status().isOk());
	}

	@Test
	void loginTokenCanRefreshAfterAccessTokenExpires() throws Exception {
		var login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(mapper.writeValueAsString(Map.of("email", "refresh@example.com", "password", PASSWORD))))
			.andExpect(status().isOk()).andReturn();
		String token = mapper.readTree(login.getResponse().getContentAsString()).get("data").get("refreshToken").asText();
		when(authClock.instant()).thenReturn(NOW.plusSeconds(4000));
		refresh(token).andExpect(status().isOk());
	}

	@ParameterizedTest
	@ValueSource(strings = {"unknown", "expired", "boundary", "revoked", "deleted-user", "access-token"})
	void invalidTokensDoNotCreateSessions(String scenario) throws Exception {
		String token = refreshToken;
		switch (scenario) {
			case "unknown" -> token = "unknown-token";
			case "expired" -> when(authClock.instant()).thenReturn(NOW.plusSeconds(3601));
			case "boundary" -> when(authClock.instant()).thenReturn(NOW.plusSeconds(3600));
			case "revoked" -> jdbcTemplate.update("update user_sessions set revoked_at = issued_at where id = ?", sessionId);
			case "deleted-user" -> jdbcTemplate.update("update users set deleted_at = CURRENT_TIMESTAMP where id = 42");
			case "access-token" -> token = tokenService.issue(42L).accessToken();
		}
		refresh(token).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
		assertThat(userSessionRepository.count()).isEqualTo(1);
	}

	@ParameterizedTest
	@ValueSource(strings = {"{}", "{\"refreshToken\":null}", "{\"refreshToken\":\"\"}",
		"{\"refreshToken\":\"   \"}", "null", "{"})
	void rejectsInvalidRequest(String body) throws Exception {
		mockMvc.perform(post("/api/auth/token/refresh").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
		assertThat(userSessionRepository.count()).isEqualTo(1);
	}

	@Test
	void rejectsOversizedToken() throws Exception {
		refresh("x".repeat(513)).andExpect(status().isBadRequest());
	}

	@Test
	void failedSessionSaveRollsBackRevocation() throws Exception {
		doThrow(new DataIntegrityViolationException("simulated failure"))
			.when(userSessionRepository).save(any(UserSession.class));
		refresh(refreshToken).andExpect(status().isInternalServerError());
		assertThat(userSessionRepository.count()).isEqualTo(1);
		assertThat(userSessionRepository.findById(sessionId).orElseThrow().getRevokedAt()).isNull();
	}

	@Test
	void simultaneousRequestsRotateOnlyOnce() throws Exception {
		var ready = new CountDownLatch(2);
		var start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			Callable<Integer> call = () -> {
				ready.countDown();
				if (!start.await(5, TimeUnit.SECONDS)) {
					throw new IllegalStateException("Refresh start timed out");
				}
				return refresh(refreshToken).andReturn().getResponse().getStatus();
			};
			var first = executor.submit(call);
			var second = executor.submit(call);
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
				.containsExactlyInAnyOrder(200, 401);
		}
		assertThat(userSessionRepository.count()).isEqualTo(2);
		assertThat(userSessionRepository.findAll()).filteredOn(s -> s.getRevokedAt() == null).hasSize(1);
	}

	@Test
	void tokenDtosDoNotExposeSecretsInToString() {
		assertThat(new RefreshRequest(refreshToken).toString()).doesNotContain(refreshToken);
		assertThat(new RefreshResponse("secret-access", refreshToken, 3600).toString())
			.doesNotContain(refreshToken, "secret-access");
	}

	private ResultActions refresh(String token) throws Exception {
		return mockMvc.perform(post("/api/auth/token/refresh").contentType(MediaType.APPLICATION_JSON)
			.content(mapper.writeValueAsString(Map.of("refreshToken", token))));
	}
}
