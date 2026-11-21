package com.vium.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.auth.service.TokenService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:push-token-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Sql(scripts = "/db/auth-users.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class PushTokenApiIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TokenService tokenService;

	private String authOf(long userId) {
		return "Bearer " + tokenService.issue(userId).accessToken();
	}

	@BeforeEach
	void setUp() {
		jdbc.update("delete from \"user_push_tokens\"");
		jdbc.update("delete from \"user_sessions\"");
		jdbc.update("delete from \"users\"");
		jdbc.update("""
			insert into "users" (id, email, display_name, created_at, updated_at)
			values (1, 'push1@example.com', '사용자1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
			       (2, 'push2@example.com', '사용자2', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""");
	}

	private List<Map<String, Object>> tokenRows() {
		return jdbc.queryForList(
			"select id, user_id, token, platform from \"user_push_tokens\" order by id");
	}

	@Test
	void registersNewTokenAndReturnsId() throws Exception {
		mockMvc.perform(put("/api/me/push-tokens").header("Authorization", authOf(1))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"token":"token-a","platform":"ANDROID"}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.pushTokenId").isNumber())
			.andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.nullValue()));

		var rows = tokenRows();
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0)).containsEntry("user_id", 1L).containsEntry("platform", "ANDROID");
	}

	@Test
	void reregisteringSameTokenBySameUserIsIdempotent() throws Exception {
		mockMvc.perform(put("/api/me/push-tokens").header("Authorization", authOf(1))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"token":"token-a","platform":"ANDROID"}
					"""))
			.andExpect(status().isOk());

		mockMvc.perform(put("/api/me/push-tokens").header("Authorization", authOf(1))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"token":"token-a","platform":"IOS"}
					"""))
			.andExpect(status().isOk());

		var rows = tokenRows();
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0)).containsEntry("user_id", 1L).containsEntry("platform", "IOS");
	}

	@Test
	void registeringSameTokenByDifferentUserMovesOwnership() throws Exception {
		mockMvc.perform(put("/api/me/push-tokens").header("Authorization", authOf(1))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"token":"token-a","platform":"ANDROID"}
					"""))
			.andExpect(status().isOk());

		mockMvc.perform(put("/api/me/push-tokens").header("Authorization", authOf(2))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"token":"token-a","platform":"IOS"}
					"""))
			.andExpect(status().isOk());

		var rows = tokenRows();
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0)).containsEntry("user_id", 2L).containsEntry("platform", "IOS");
	}

	@Test
	void sameUserCanRegisterMultipleDevices() throws Exception {
		mockMvc.perform(put("/api/me/push-tokens").header("Authorization", authOf(1))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"token":"token-a","platform":"ANDROID"}
					"""))
			.andExpect(status().isOk());

		mockMvc.perform(put("/api/me/push-tokens").header("Authorization", authOf(1))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"token":"token-b","platform":"IOS"}
					"""))
			.andExpect(status().isOk());

		var rows = tokenRows();
		assertThat(rows).hasSize(2);
		assertThat(rows).extracting(r -> r.get("user_id")).containsExactly(1L, 1L);
	}

	@Test
	void unregisterDeletesOwnToken() throws Exception {
		mockMvc.perform(put("/api/me/push-tokens").header("Authorization", authOf(1))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"token":"token-a","platform":"ANDROID"}
					"""))
			.andExpect(status().isOk());

		mockMvc.perform(post("/api/me/push-tokens/unregister").header("Authorization", authOf(1))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"token":"token-a"}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));

		assertThat(tokenRows()).isEmpty();
	}

	@Test
	void unregisterIsIdempotentForMissingToken() throws Exception {
		mockMvc.perform(post("/api/me/push-tokens/unregister").header("Authorization", authOf(1))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"token":"does-not-exist"}
					"""))
			.andExpect(status().isOk());
	}

	@Test
	void unregisterOfAnotherUsersTokenDoesNothingButReturns200() throws Exception {
		mockMvc.perform(put("/api/me/push-tokens").header("Authorization", authOf(1))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"token":"token-a","platform":"ANDROID"}
					"""))
			.andExpect(status().isOk());

		mockMvc.perform(post("/api/me/push-tokens/unregister").header("Authorization", authOf(2))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"token":"token-a"}
					"""))
			.andExpect(status().isOk());

		var rows = tokenRows();
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0)).containsEntry("user_id", 1L);
	}

	@Test
	void registerRejectsBlankToken() throws Exception {
		mockMvc.perform(put("/api/me/push-tokens").header("Authorization", authOf(1))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"token":"","platform":"ANDROID"}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	void registerRejectsTokenLongerThan512Chars() throws Exception {
		String tooLong = "a".repeat(513);
		mockMvc.perform(put("/api/me/push-tokens").header("Authorization", authOf(1))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"" + tooLong + "\",\"platform\":\"ANDROID\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	void registerRejectsInvalidPlatform() throws Exception {
		mockMvc.perform(put("/api/me/push-tokens").header("Authorization", authOf(1))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"token":"token-a","platform":"WEB"}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	void registerRejectsMissingPlatform() throws Exception {
		mockMvc.perform(put("/api/me/push-tokens").header("Authorization", authOf(1))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"token":"token-a"}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	void registerWithoutAuthIsUnauthorized() throws Exception {
		mockMvc.perform(put("/api/me/push-tokens")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"token":"token-a","platform":"ANDROID"}
					"""))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void unregisterWithoutAuthIsUnauthorized() throws Exception {
		mockMvc.perform(post("/api/me/push-tokens/unregister")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"token":"token-a"}
					"""))
			.andExpect(status().isUnauthorized());
	}
}
