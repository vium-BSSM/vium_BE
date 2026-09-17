package com.vium.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.vium.auth.entity.UserSession;
import com.vium.auth.repository.UserSessionRepository;
import com.vium.global.security.JwtProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:auth-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Sql(scripts = "/db/auth-users.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class AuthIntegrationTest {
	@Autowired private MockMvc mockMvc;
	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private PasswordEncoder passwordEncoder;
	@Autowired private JwtProperties jwtProperties;
	@MockitoSpyBean private UserSessionRepository userSessionRepository;

	private static final String PASSWORD = "P@ssw0rd123";
	private static final String REQUEST = "{\"email\":\"user@example.com\",\"password\":\"P@ssw0rd123\"}";

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("delete from user_sessions");
		jdbcTemplate.update("delete from users");
		jdbcTemplate.update("""
			insert into users (id,email,display_name,password_hash,created_at,updated_at)
			values (42,'user@example.com','테스트 사용자',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
			""", passwordEncoder.encode(PASSWORD));
	}

	@Test
	void loginWithoutBearerReturnsSignedTokensAndCommitsHashedSession() throws Exception {
		Instant before = Instant.now().minusSeconds(1);
		JsonNode response = login();
		assertThat(response.get("success").asBoolean()).isTrue();
		assertThat(response.get("error").isNull()).isTrue();
		JsonNode data = response.get("data");
		assertThat(data.get("user").get("id").asLong()).isEqualTo(42);
		assertThat(data.get("user").get("email").asText()).isEqualTo("user@example.com");
		assertThat(data.get("user").get("displayName").asText()).isEqualTo("테스트 사용자");
		assertThat(data.get("user").has("passwordHash")).isFalse();
		String access = data.get("accessToken").asText();
		String refresh = data.get("refreshToken").asText();
		SignedJWT accessJwt = verify(access, "access", 3600);
		SignedJWT refreshJwt = verify(refresh, "refresh", 1209600);
		assertThat(access).isNotEqualTo(refresh);
		assertThat(accessJwt.getJWTClaimsSet().getIssueTime().toInstant()).isAfterOrEqualTo(before);
		assertThat(userSessionRepository.findAll()).singleElement().satisfies(session -> {
			assertThat(session.getUserId()).isEqualTo(42L);
			assertThat(session.getRefreshTokenHash()).isNotEqualTo(refresh).hasSize(64);
			assertThat(session.getRevokedAt()).isNull();
		});
		UserSession session = userSessionRepository.findAll().getFirst();
		assertThat(session.getRefreshTokenHash()).isEqualTo(HexFormat.of().formatHex(
			MessageDigest.getInstance("SHA-256").digest(refresh.getBytes(StandardCharsets.UTF_8))));
		assertThat(session.getExpiresAt().toInstant(ZoneOffset.UTC))
			.isEqualTo(refreshJwt.getJWTClaimsSet().getExpirationTime().toInstant());
	}

	@Test
	void repeatedLoginCreatesDistinctTokensAndSessions() throws Exception {
		JsonNode first = login().get("data");
		JsonNode second = login().get("data");
		assertThat(first.get("accessToken").asText()).isNotEqualTo(second.get("accessToken").asText());
		assertThat(first.get("refreshToken").asText()).isNotEqualTo(second.get("refreshToken").asText());
		assertThat(userSessionRepository.count()).isEqualTo(2);
	}

	@ParameterizedTest
	@ValueSource(strings = {"wrong-password", "unknown-email", "deleted", "social-only", "malformed-hash"})
	void invalidCredentialsShareSameUnauthorizedResponseAndCreateNoSession(String scenario) throws Exception {
		String body = REQUEST;
		switch (scenario) {
			case "wrong-password" -> body = REQUEST.replace(PASSWORD, "wrong-password");
			case "unknown-email" -> body = REQUEST.replace("user@example.com", "unknown@example.com");
			case "deleted" -> jdbcTemplate.update("update users set deleted_at=CURRENT_TIMESTAMP where id=42");
			case "social-only" -> jdbcTemplate.update("update users set password_hash=null where id=42");
			case "malformed-hash" -> jdbcTemplate.update("update users set password_hash='not-a-password-hash' where id=42");
		}
		mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.message").value("이메일 또는 비밀번호가 올바르지 않습니다"));
		assertThat(userSessionRepository.count()).isZero();
	}

	@ParameterizedTest
	@ValueSource(strings = {"{}", "{\"email\":\"user@example.com\"}",
		"{\"email\":\"bad-email\",\"password\":\"test\"}",
		"{\"email\":\"user@example.com\",\"password\":\"   \"}",
		"{\"email\":null,\"password\":null}", "{", "null"})
	void rejectsInvalidRequest(String body) throws Exception {
		mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
		assertThat(userSessionRepository.count()).isZero();
	}

	@Test
	void rejectsBcryptPasswordOver72Utf8Bytes() throws Exception {
		String body = REQUEST.replace(PASSWORD, "가".repeat(25));
		mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isBadRequest());
		assertThat(userSessionRepository.count()).isZero();
	}

	@Test
	void doesNotReturnTokensIfSessionCannotBeSaved() throws Exception {
		doThrow(new DataIntegrityViolationException("session write failed"))
			.when(userSessionRepository).save(any(UserSession.class));
		mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.data.accessToken").doesNotExist());
		assertThat(userSessionRepository.count()).isZero();
	}

	private JsonNode login() throws Exception {
		String body = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
			.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
			.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		return JsonMapper.builder().build().readTree(body);
	}

	private SignedJWT verify(String token, String type, long ttl) throws Exception {
		SignedJWT jwt = SignedJWT.parse(token);
		assertThat(jwt.verify(new MACVerifier(Base64.getDecoder().decode(jwtProperties.secret())))).isTrue();
		assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo("HS256");
		assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo("42");
		assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("vium-test");
		assertThat(jwt.getJWTClaimsSet().getStringClaim("token_type")).isEqualTo(type);
		assertThat(jwt.getJWTClaimsSet().getJWTID()).isNotBlank();
		assertThat(Duration.between(jwt.getJWTClaimsSet().getIssueTime().toInstant(),
			jwt.getJWTClaimsSet().getExpirationTime().toInstant()).getSeconds()).isEqualTo(ttl);
		return jwt;
	}
}
