package com.vium.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.auth.dto.RegisterRequest;
import java.sql.Timestamp;
import java.util.HashMap;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:register-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Sql(scripts = "/db/auth-users.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class RegisterIntegrationTest {
	@Autowired private MockMvc mockMvc;
	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private PasswordEncoder passwordEncoder;
	@Autowired private JsonMapper mapper;

	private static final String EMAIL = "register@example.com";
	private static final String PASSWORD = "correct horse battery";

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("delete from user_sessions");
		jdbcTemplate.update("delete from users");
	}

	@Test
	void registersWithoutBearerStoresHashAndCanLogin() throws Exception {
		var result = mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content(mapper.writeValueAsString(validRequest())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.email").value(EMAIL))
			.andExpect(jsonPath("$.data.displayName").value("가입 사용자"))
			.andExpect(jsonPath("$.data.password").doesNotExist())
			.andExpect(jsonPath("$.data.passwordHash").doesNotExist())
			.andExpect(jsonPath("$.data.accessToken").doesNotExist())
			.andExpect(jsonPath("$.error").value(nullValue()))
			.andReturn();
		long id = mapper.readTree(result.getResponse().getContentAsString()).get("data").get("userId").asLong();
		assertThat(id).isPositive();
		String hash = jdbcTemplate.queryForObject("select password_hash from users where id = ?", String.class, id);
		assertThat(hash).isNotEqualTo(PASSWORD);
		assertThat(passwordEncoder.matches(PASSWORD, hash)).isTrue();
		assertThat(jdbcTemplate.queryForObject("select expiry_alert_days from users where id = ?", Integer.class, id))
			.isEqualTo(2);
		assertThat(jdbcTemplate.queryForObject("select count(*) from user_sessions", Long.class)).isZero();
		mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(mapper.writeValueAsString(Map.of("email", EMAIL, "password", PASSWORD))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.user.id").value(id))
			.andExpect(jsonPath("$.data.accessToken").isString());
	}

	@ParameterizedTest
	@ValueSource(strings = {"active", "deleted", "social"})
	void rejectsDuplicateEmailWithoutChangingExistingAccount(String kind) throws Exception {
		jdbcTemplate.update("""
			insert into users (email,display_name,password_hash,created_at,updated_at,deleted_at)
			values (?,'기존 사용자',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?)
			""", EMAIL, kind.equals("social") ? null : "original-hash",
			kind.equals("deleted") ? Timestamp.valueOf("2026-01-01 00:00:00") : null);
		mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content(mapper.writeValueAsString(validRequest())))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").value(nullValue()))
			.andExpect(jsonPath("$.error.code").value("CONFLICT"));
		assertThat(jdbcTemplate.queryForObject("select count(*) from users", Long.class)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("select display_name from users where email = ?", String.class, EMAIL))
			.isEqualTo("기존 사용자");
	}

	@ParameterizedTest
	@ValueSource(strings = {"email", "password", "displayName"})
	void rejectsMissingNullAndBlankFields(String field) throws Exception {
		var request = validRequest();
		request.remove(field);
		assertInvalid(request);
		request.put(field, null);
		assertInvalid(request);
		request.put(field, "   ");
		assertInvalid(request);
	}

	@Test
	void rejectsInvalidEmailAndOversizedFields() throws Exception {
		var request = validRequest();
		request.put("email", "invalid-email");
		assertInvalid(request);
		request = validRequest();
		request.put("email", "a".repeat(250) + "@example.com");
		assertInvalid(request);
		request = validRequest();
		request.put("displayName", "a".repeat(81));
		assertInvalid(request);
		request = validRequest();
		request.put("password", "a".repeat(73));
		assertInvalid(request);
		request.put("password", "가".repeat(25));
		assertInvalid(request);
	}

	@Test
	void acceptsUtf8PasswordAtBcryptLimit() throws Exception {
		var request = validRequest();
		request.put("password", "가".repeat(24));
		mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content(mapper.writeValueAsString(request)))
			.andExpect(status().isOk());
		String hash = jdbcTemplate.queryForObject("select password_hash from users where email = ?", String.class, EMAIL);
		assertThat(passwordEncoder.matches(request.get("password"), hash)).isTrue();
	}

	@Test
	void simultaneousRegistrationsCreateOnlyOneAccount() throws Exception {
		String body = mapper.writeValueAsString(validRequest());
		var ready = new CountDownLatch(2);
		var start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			Callable<Integer> register = () -> {
				ready.countDown();
				if (!start.await(5, TimeUnit.SECONDS)) {
					throw new IllegalStateException("Registration start timed out");
				}
				return mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
					.content(body)).andReturn().getResponse().getStatus();
			};
			var first = executor.submit(register);
			var second = executor.submit(register);
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			assertThat(List.of(first.get(10, TimeUnit.SECONDS),
				second.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
		}
		assertThat(jdbcTemplate.queryForObject("select count(*) from users", Long.class)).isEqualTo(1);
	}

	@Test
	void normalizesEmailAndDisplayNameBeforeValidationAndStorage() throws Exception {
		var request = validRequest();
		request.put("email", "  REGISTER@Example.COM  ");
		request.put("displayName", "　 가입 사용자 　");
		mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content(mapper.writeValueAsString(request)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.email").value(EMAIL))
			.andExpect(jsonPath("$.data.displayName").value("가입 사용자"));
		assertThat(jdbcTemplate.queryForObject("select email from users", String.class)).isEqualTo(EMAIL);
		assertThat(jdbcTemplate.queryForObject("select display_name from users", String.class)).isEqualTo("가입 사용자");
		mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(mapper.writeValueAsString(Map.of("email", "  Register@EXAMPLE.COM ", "password", PASSWORD))))
			.andExpect(status().isOk());
		mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content(mapper.writeValueAsString(validRequest())))
			.andExpect(status().isConflict());
	}

	@ParameterizedTest
	@ValueSource(strings = {"a", "1234567", "😀😀😀😀"})
	void rejectsShortPasswords(String password) throws Exception {
		var request = validRequest();
		request.put("password", password);
		assertInvalid(request);
	}

	@ParameterizedTest
	@ValueSource(strings = {"abcdefgh", "　abcdefgh　"})
	void acceptsMinimumLengthWithoutCompositionRulesAndPreservesPassword(String password) throws Exception {
		var request = validRequest();
		request.put("password", password);
		mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content(mapper.writeValueAsString(request)))
			.andExpect(status().isOk());
		String hash = jdbcTemplate.queryForObject("select password_hash from users", String.class);
		assertThat(passwordEncoder.matches(password, hash)).isTrue();
	}

	@Test
	void requestToStringDoesNotExposeCredentials() {
		assertThat(new RegisterRequest(EMAIL, PASSWORD, "가입 사용자").toString())
			.doesNotContain(EMAIL, PASSWORD);
	}

	private Map<String, String> validRequest() {
		return new HashMap<>(Map.of("email", EMAIL, "password", PASSWORD, "displayName", "가입 사용자"));
	}

	private void assertInvalid(Map<String, String> request) throws Exception {
		mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content(mapper.writeValueAsString(request)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
		assertThat(jdbcTemplate.queryForObject("select count(*) from users", Long.class)).isZero();
	}
}
