package com.vium.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.auth.entity.UserSession;
import com.vium.auth.repository.UserSessionRepository;
import com.vium.auth.service.TokenService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:account-deletion-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
	"images.storage.public-base-url=https://images.example.com"
})
@AutoConfigureMockMvc
@Sql(scripts = "/db/auth-users.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class AccountDeletionIntegrationTest {
	@Autowired private MockMvc mockMvc;
	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private TokenService tokenService;
	@Autowired private UserSessionRepository userSessionRepository;
	@Autowired private PasswordEncoder passwordEncoder;
	@Autowired private JsonMapper mapper;
	@MockitoBean private Clock authClock;

	private static final Instant NOW = Instant.parse("2026-09-30T00:00:00Z");
	private TokenService.TokenPair tokens;
	private TokenService.TokenPair otherTokens;

	@BeforeEach
	void setUp() {
		when(authClock.instant()).thenReturn(NOW);
		jdbcTemplate.update("delete from user_sessions");
		jdbcTemplate.update("update users set profile_image_id = null");
		jdbcTemplate.update("delete from uploaded_images");
		jdbcTemplate.update("delete from users");
		jdbcTemplate.update("""
			insert into users (id,email,display_name,password_hash,created_at,updated_at)
			values (42,'deletion@example.com','Deletion User','secret-hash',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
			       (43,'other@example.com','Other User','secret-hash',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
			""");
		tokens = issueAndPersistSession(42L);
		otherTokens = issueAndPersistSession(43L);
	}

	@Test
	void deletesAccountAndItsSessionsOnly() throws Exception {
		deleteAccount(tokens.accessToken()).andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.nullValue()));

		var deletedAt = jdbcTemplate.queryForObject(
			"select deleted_at from users where id = 42", Timestamp.class);
		assertThat(deletedAt.toInstant()).isEqualTo(NOW);
		assertThat(userSessionRepository.findAll()).extracting(UserSession::getUserId).containsExactly(43L);

		var otherDeletedAt = jdbcTemplate.queryForObject(
			"select deleted_at from users where id = 43", Timestamp.class);
		assertThat(otherDeletedAt).isNull();
	}

	@Test
	void rejectsSubsequentAccessToMeAfterDeletion() throws Exception {
		deleteAccount(tokens.accessToken()).andExpect(status().isOk());

		assertUnauthorized(mockMvc.perform(get("/api/me")
			.header("Authorization", "Bearer " + tokens.accessToken())));
	}

	@Test
	void rejectsProfileUpdateAfterDeletion() throws Exception {
		deleteAccount(tokens.accessToken()).andExpect(status().isOk());

		assertUnauthorized(mockMvc.perform(patch("/api/me").contentType(MediaType.APPLICATION_JSON)
			.header("Authorization", "Bearer " + tokens.accessToken())
			.content("{\"displayName\":\"New Name\"}")));
	}

	@Test
	void rejectsTokenRefreshAfterDeletion() throws Exception {
		deleteAccount(tokens.accessToken()).andExpect(status().isOk());

		mockMvc.perform(post("/api/auth/token/refresh").contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"" + tokens.refreshToken() + "\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
	}

	@Test
	void rejectsDeleteWithoutToken() throws Exception {
		assertUnauthorized(mockMvc.perform(delete("/api/me")));
	}

	@Test
	void rejectsRepeatedDeleteWithSameTokenAfterAlreadyDeleted() throws Exception {
		deleteAccount(tokens.accessToken()).andExpect(status().isOk());

		assertUnauthorized(deleteAccount(tokens.accessToken()));
	}

	@Test
	void doesNotAffectOtherUsersSessionAfterDeletion() throws Exception {
		deleteAccount(tokens.accessToken()).andExpect(status().isOk());

		mockMvc.perform(post("/api/auth/token/refresh").contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"" + otherTokens.refreshToken() + "\"}"))
			.andExpect(status().isOk());
	}

	@Test
	void rejectsRegistrationWithDeletedAccountsEmail() throws Exception {
		deleteAccount(tokens.accessToken()).andExpect(status().isOk());

		mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"deletion@example.com","password":"new-password1","displayName":"New User"}
					"""))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()))
			.andExpect(jsonPath("$.error.code").value("CONFLICT"));
	}

	@Test
	void rejectsLoginAfterDeletionButSucceedsBeforeDeletion() throws Exception {
		String email = "login-check@example.com";
		String password = "correct horse battery";
		jdbcTemplate.update("""
			insert into users (id,email,display_name,password_hash,created_at,updated_at)
			values (44,?,'Login User',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
			""", email, passwordEncoder.encode(password));
		String loginBody = "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";

		var loginResult = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(loginBody))
			.andExpect(status().isOk())
			.andReturn();
		String accessToken = mapper.readTree(loginResult.getResponse().getContentAsString())
			.get("data").get("accessToken").asText();

		deleteAccount(accessToken).andExpect(status().isOk());

		mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(loginBody))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
	}

	private ResultActions deleteAccount(String token) throws Exception {
		return mockMvc.perform(delete("/api/me").header("Authorization", "Bearer " + token));
	}

	private TokenService.TokenPair issueAndPersistSession(Long userId) {
		var pair = tokenService.issue(userId);
		userSessionRepository.save(UserSession.create(userId, pair.refreshToken(), pair.issuedAt(), pair.refreshExpiresAt()));
		return pair;
	}

	private void assertUnauthorized(ResultActions result) throws Exception {
		result.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()))
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
	}
}
