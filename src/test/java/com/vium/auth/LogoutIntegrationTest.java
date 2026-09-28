package com.vium.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.auth.dto.LogoutRequest;
import com.vium.auth.entity.UserSession;
import com.vium.auth.repository.UserSessionRepository;
import com.vium.auth.service.TokenService;
import com.vium.global.security.JwtProperties;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:logout-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Sql(scripts = "/db/auth-users.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class LogoutIntegrationTest {
	@Autowired private MockMvc mockMvc;
	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private TokenService tokenService;
	@Autowired private UserSessionRepository userSessionRepository;
	@Autowired private JwtDecoder jwtDecoder;
	@Autowired private JwtProperties jwtProperties;
	@Autowired private JsonMapper mapper;
	@MockitoBean private Clock authClock;

	private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");
	private TokenService.TokenPair tokens;
	private Long sessionId;

	@BeforeEach
	void setUp() {
		when(authClock.instant()).thenReturn(NOW);
		jdbcTemplate.update("delete from user_sessions");
		jdbcTemplate.update("delete from users");
		jdbcTemplate.update("""
			insert into users (id,email,display_name,created_at,updated_at)
			values (42,'logout@example.com','사용자',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
			       (43,'other@example.com','다른 사용자',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
			""");
		tokens = tokenService.issue(42L);
		sessionId = userSessionRepository.saveAndFlush(UserSession.create(42L, tokens.refreshToken(),
			tokens.issuedAt(), tokens.refreshExpiresAt())).getId();
	}

	@Test
	void logoutRevokesOnlySubmittedSessionAndBlocksRefresh() throws Exception {
		var other = tokenService.issue(42L);
		Long otherId = userSessionRepository.saveAndFlush(UserSession.create(42L, other.refreshToken(),
			other.issuedAt(), other.refreshExpiresAt())).getId();
		logout(tokens.accessToken(), tokens.refreshToken()).andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data").value(nullValue()))
			.andExpect(jsonPath("$.error").value(nullValue()));
		assertThat(userSessionRepository.findById(sessionId).orElseThrow().getRevokedAt())
			.isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
		assertThat(userSessionRepository.findById(otherId).orElseThrow().getRevokedAt()).isNull();
		mockMvc.perform(post("/api/auth/token/refresh").contentType(MediaType.APPLICATION_JSON)
				.content(body(tokens.refreshToken())))
			.andExpect(status().isUnauthorized());
		assertThat(userSessionRepository.count()).isEqualTo(2);
		assertThat(jwtDecoder.decode(tokens.accessToken()).getSubject()).isEqualTo("42");
	}

	@Test
	void repeatedLogoutPreservesOriginalRevocationTime() throws Exception {
		logout(tokens.accessToken(), tokens.refreshToken()).andExpect(status().isOk());
		when(authClock.instant()).thenReturn(NOW.plusSeconds(10));
		logout(tokens.accessToken(), tokens.refreshToken()).andExpect(status().isOk());
		assertThat(userSessionRepository.findById(sessionId).orElseThrow().getRevokedAt())
			.isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
	}

	@Test
	void unknownTokenIsAnIdempotentSuccess() throws Exception {
		logout(tokens.accessToken(), "unknown-token").andExpect(status().isOk());
		assertNotRevoked();
	}

	@Test
	void expiredRefreshTokenCanStillBeRevoked() throws Exception {
		jdbcTemplate.update("update user_sessions set expires_at = ? where id = ?",
			LocalDateTime.ofInstant(NOW, ZoneOffset.UTC), sessionId);
		logout(tokens.accessToken(), tokens.refreshToken()).andExpect(status().isOk());
		assertThat(userSessionRepository.findById(sessionId).orElseThrow().getRevokedAt()).isNotNull();
	}

	@Test
	void anotherUserCannotRevokeSession() throws Exception {
		logout(tokenService.issue(43L).accessToken(), tokens.refreshToken())
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
		assertNotRevoked();
	}

	@ParameterizedTest
	@ValueSource(strings = {"missing", "malformed", "expired"})
	void requiresValidAccessToken(String kind) throws Exception {
		var request = post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON).content(body(tokens.refreshToken()));
		if (kind.equals("malformed")) {
			request.header("Authorization", "Bearer invalid-token");
		} else if (kind.equals("expired")) {
			when(authClock.instant()).thenReturn(NOW.plusSeconds(
				jwtProperties.accessTokenSeconds() + jwtProperties.clockSkewSeconds() + 1));
			request.header("Authorization", "Bearer " + tokens.accessToken());
		}
		mockMvc.perform(request).andExpect(status().isUnauthorized());
		assertNotRevoked();
	}

	@ParameterizedTest
	@ValueSource(strings = {"{}", "{\"refreshToken\":null}", "{\"refreshToken\":\"\"}",
		"{\"refreshToken\":\"   \"}", "null", "{"})
	void rejectsInvalidBody(String body) throws Exception {
		mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + tokens.accessToken())
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isBadRequest());
		assertNotRevoked();
	}

	@Test
	void rejectsOversizedToken() throws Exception {
		logout(tokens.accessToken(), "x".repeat(513)).andExpect(status().isBadRequest());
		assertNotRevoked();
	}

	@Test
	void simultaneousLogoutsAreIdempotent() throws Exception {
		var ready = new CountDownLatch(2);
		var start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			Callable<Integer> call = () -> {
				ready.countDown();
				if (!start.await(5, TimeUnit.SECONDS)) {
					throw new IllegalStateException("Logout start timed out");
				}
				return logout(tokens.accessToken(), tokens.refreshToken()).andReturn().getResponse().getStatus();
			};
			var first = executor.submit(call);
			var second = executor.submit(call);
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
				.containsExactly(200, 200);
		}
		assertThat(userSessionRepository.count()).isEqualTo(1);
		assertThat(userSessionRepository.findById(sessionId).orElseThrow().getRevokedAt()).isNotNull();
	}

	@Test
	void concurrentRefreshAndLogoutLeaveOnlyASerializedOutcome() throws Exception {
		var ready = new CountDownLatch(2);
		var start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var logoutFuture = executor.submit(() -> {
				awaitStart(ready, start);
				return logout(tokens.accessToken(), tokens.refreshToken()).andReturn();
			});
			var refreshFuture = executor.submit(() -> {
				awaitStart(ready, start);
				return mockMvc.perform(post("/api/auth/token/refresh").contentType(MediaType.APPLICATION_JSON)
					.content(body(tokens.refreshToken()))).andReturn();
			});
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			assertThat(logoutFuture.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
			var refreshed = refreshFuture.get(10, TimeUnit.SECONDS).getResponse();
			assertThat(refreshed.getStatus()).isIn(200, 401);
			var sessions = userSessionRepository.findAll();
			assertThat(userSessionRepository.findById(sessionId).orElseThrow().getRevokedAt()).isNotNull();
			if (refreshed.getStatus() == 200) {
				String replacement = mapper.readTree(refreshed.getContentAsString())
					.get("data").get("refreshToken").asText();
				assertThat(sessions).hasSize(2);
				assertThat(sessions).filteredOn(s -> s.getRevokedAt() == null).singleElement()
					.satisfies(s -> {
						assertThat(s.getUserId()).isEqualTo(42L);
						assertThat(s.getRefreshTokenHash()).isEqualTo(UserSession.hashToken(replacement));
					});
			} else {
				assertThat(sessions).hasSize(1).allSatisfy(s -> assertThat(s.getRevokedAt()).isNotNull());
				assertThat(mapper.readTree(refreshed.getContentAsString()).get("error").get("code").asText())
					.isEqualTo("UNAUTHORIZED");
			}
		}
	}

	private void awaitStart(CountDownLatch ready, CountDownLatch start) throws InterruptedException {
		ready.countDown();
		if (!start.await(5, TimeUnit.SECONDS)) {
			throw new IllegalStateException("Concurrent requests start timed out");
		}
	}

	@Test
	void loggingOutRotatedTokenDoesNotRevokeReplacementSession() throws Exception {
		var result = mockMvc.perform(post("/api/auth/token/refresh").contentType(MediaType.APPLICATION_JSON)
				.content(body(tokens.refreshToken())))
			.andExpect(status().isOk()).andReturn();
		String replacement = mapper.readTree(result.getResponse().getContentAsString())
			.get("data").get("refreshToken").asText();
		logout(tokens.accessToken(), tokens.refreshToken()).andExpect(status().isOk());
		assertThat(userSessionRepository.findAll()).filteredOn(s -> s.getRevokedAt() == null).hasSize(1);
		logout(tokens.accessToken(), replacement).andExpect(status().isOk());
		assertThat(userSessionRepository.findAll()).allSatisfy(s -> assertThat(s.getRevokedAt()).isNotNull());
	}

	@Test
	void requestToStringDoesNotExposeToken() {
		assertThat(new LogoutRequest(tokens.refreshToken()).toString()).doesNotContain(tokens.refreshToken());
	}

	private void assertNotRevoked() {
		assertThat(userSessionRepository.findById(sessionId).orElseThrow().getRevokedAt()).isNull();
		assertThat(userSessionRepository.count()).isEqualTo(1);
	}

	private String body(String token) {
		return mapper.writeValueAsString(Map.of("refreshToken", token));
	}

	private ResultActions logout(String access, String refresh) throws Exception {
		return mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + access)
			.contentType(MediaType.APPLICATION_JSON).content(body(refresh)));
	}
}
