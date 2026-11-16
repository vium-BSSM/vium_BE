package com.vium.safety;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.auth.service.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:report-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Sql(scripts = "/db/auth-users.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class ReportControllerIntegrationTest {

	private static final Long REPORTER_ID = 42L;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TokenService tokenService;

	private String bearer;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("""
			create table if not exists "posts"
			("id" bigint primary key, "author_id" bigint not null, "deleted_at" timestamp)
			""");
		jdbcTemplate.update("delete from user_reports");
		jdbcTemplate.update("delete from posts");
		jdbcTemplate.update("delete from users");
		jdbcTemplate.update("""
			insert into users (id, email, display_name, password_hash, created_at, updated_at, deleted_at)
			values (42, 'reporter@example.com', 'Reporter', null, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, null),
			       (43, 'target@example.com', 'Target', null, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, null),
			       (44, 'withdrawn@example.com', 'Withdrawn', null, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""");
		jdbcTemplate.update("""
			insert into posts (id, author_id, deleted_at) values
			(100, 43, null),
			(101, 42, null),
			(102, 43, CURRENT_TIMESTAMP)
			""");
		bearer = "Bearer " + tokenService.issue(REPORTER_ID).accessToken();
	}

	private org.springframework.test.web.servlet.ResultActions report(String body) throws Exception {
		return mockMvc.perform(post("/api/me/reports").header("Authorization", bearer)
			.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	@Test
	void reportsUserSuccessfully() throws Exception {
		report("""
			{"targetType":"USER","targetId":43,"reason":"SPAM"}
			""")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.reportId").exists());
	}

	@Test
	void rejectsSelfReport() throws Exception {
		report("""
			{"targetType":"USER","targetId":42,"reason":"SPAM"}
			""")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	void rejectsOwnPostReport() throws Exception {
		report("""
			{"targetType":"POST","targetId":101,"reason":"SPAM"}
			""")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	void rejectsMissingUser() throws Exception {
		report("""
			{"targetType":"USER","targetId":999999,"reason":"SPAM"}
			""")
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));
	}

	@Test
	void rejectsWithdrawnUser() throws Exception {
		report("""
			{"targetType":"USER","targetId":44,"reason":"SPAM"}
			""")
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));
	}

	@Test
	void rejectsDeletedPost() throws Exception {
		report("""
			{"targetType":"POST","targetId":102,"reason":"SPAM"}
			""")
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"));
	}

	@Test
	void rejectsDuplicateReport() throws Exception {
		report("""
			{"targetType":"USER","targetId":43,"reason":"SPAM"}
			""").andExpect(status().isOk());

		report("""
			{"targetType":"USER","targetId":43,"reason":"FRAUD"}
			""")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("ALREADY_REPORTED"));
	}

	@Test
	void rejectsEtcReasonWithoutDetail() throws Exception {
		report("""
			{"targetType":"USER","targetId":43,"reason":"ETC"}
			""")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	void acceptsEtcReasonWithDetail() throws Exception {
		report("""
			{"targetType":"USER","targetId":43,"reason":"ETC","detail":"욕설을 했습니다"}
			""")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.reportId").exists());
	}
}
