package com.vium.safety;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.auth.service.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:user-block-controller-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Sql(scripts = "/db/auth-users.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class UserBlockControllerIntegrationTest {

	private static final Long SELF_ID = 42L;
	private static final Long TARGET_ID = 43L;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TokenService tokenService;

	private String bearer;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("delete from user_blocks");
		jdbcTemplate.update("delete from users");
		jdbcTemplate.update("""
			insert into users (id, email, display_name, password_hash, created_at, updated_at, deleted_at)
			values (42, 'self@example.com', 'Self', null, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, null),
			       (43, 'target@example.com', 'Target', null, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, null),
			       (44, 'withdrawn@example.com', 'Withdrawn', null, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""");
		bearer = "Bearer " + tokenService.issue(SELF_ID).accessToken();
	}

	@Test
	void blocksListsThenUnblocks() throws Exception {
		mockMvc.perform(put("/api/me/blocks/{userId}", TARGET_ID).header("Authorization", bearer))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.userId").value(43))
			.andExpect(jsonPath("$.data.blocked").value(true));

		mockMvc.perform(get("/api/me/blocks").header("Authorization", bearer))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.blockedUsers.length()").value(1))
			.andExpect(jsonPath("$.data.blockedUsers[0].userId").value(43))
			.andExpect(jsonPath("$.data.blockedUsers[0].displayName").value("Target"))
			.andExpect(jsonPath("$.data.blockedUsers[0].blockedAt").exists());

		mockMvc.perform(delete("/api/me/blocks/{userId}", TARGET_ID).header("Authorization", bearer))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.userId").value(43))
			.andExpect(jsonPath("$.data.blocked").value(false));

		mockMvc.perform(get("/api/me/blocks").header("Authorization", bearer))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.blockedUsers.length()").value(0));
	}

	@Test
	void blockIsIdempotent() throws Exception {
		mockMvc.perform(put("/api/me/blocks/{userId}", TARGET_ID).header("Authorization", bearer))
			.andExpect(status().isOk());
		mockMvc.perform(put("/api/me/blocks/{userId}", TARGET_ID).header("Authorization", bearer))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.blocked").value(true));

		Integer count = jdbcTemplate.queryForObject(
			"select count(*) from user_blocks where blocker_id = 42 and blocked_id = 43", Integer.class);
		org.assertj.core.api.Assertions.assertThat(count).isEqualTo(1);
	}

	@Test
	void unblockIsIdempotentWhenNotBlocked() throws Exception {
		mockMvc.perform(delete("/api/me/blocks/{userId}", TARGET_ID).header("Authorization", bearer))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.blocked").value(false));
	}

	@Test
	void rejectsSelfBlock() throws Exception {
		mockMvc.perform(put("/api/me/blocks/{userId}", SELF_ID).header("Authorization", bearer))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	void rejectsMissingUserOnBlock() throws Exception {
		mockMvc.perform(put("/api/me/blocks/{userId}", 999999L).header("Authorization", bearer))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));
	}

	@Test
	void rejectsWithdrawnUserOnUnblock() throws Exception {
		mockMvc.perform(delete("/api/me/blocks/{userId}", 44L).header("Authorization", bearer))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));
	}
}
