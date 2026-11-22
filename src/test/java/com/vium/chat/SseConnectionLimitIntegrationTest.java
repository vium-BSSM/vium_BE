package com.vium.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import com.vium.auth.service.TokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import com.vium.notification.sse.SseConnectionRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 사용자당 연결 상한(2)을 작게 설정한 전용 컨텍스트에서, 상한 초과 시 가장 오래된 연결만 종료되고
 * 나머지는 유지되는지 확인한다.
 */
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:sse-connection-limit-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
	"sse.max-connections-per-user=2"
})
@AutoConfigureMockMvc
@Sql(scripts = "/db/auth-users.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class SseConnectionLimitIntegrationTest {

	private static final Long USER_ID = 42L;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TokenService tokenService;

	@Autowired
	private SseConnectionRegistry registry;

	@BeforeEach
	void setUp() {
		jdbc.update("delete from \"users\"");
		jdbc.update("""
			insert into "users" (id, email, display_name, created_at, updated_at)
			values (?, 'user@example.com', '사용자', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""", USER_ID);
	}

	@AfterEach
	void tearDown() {
		registry.shutdown();
	}

	private MvcResult connect() throws Exception {
		return mockMvc.perform(get("/api/me/events")
				.header("Authorization", "Bearer " + tokenService.issue(USER_ID).accessToken()))
			.andExpect(request().asyncStarted())
			.andReturn();
	}

	private void awaitContains(MvcResult result, String needle) throws Exception {
		long deadline = System.currentTimeMillis() + 2000;
		while (System.currentTimeMillis() < deadline) {
			if (result.getResponse().getContentAsString().contains(needle)) {
				return;
			}
			Thread.sleep(20);
		}
	}

	@Test
	void exceedingCapClosesOnlyTheOldestConnection() throws Exception {
		MvcResult first = connect();
		MvcResult second = connect();
		MvcResult third = connect();
		awaitContains(second, "event:ping");
		awaitContains(third, "event:ping");

		registry.broadcast(USER_ID, "chat-message", "x");
		awaitContains(second, "event:chat-message");
		awaitContains(third, "event:chat-message");

		assertThat(first.getResponse().getContentAsString()).doesNotContain("event:chat-message");
		assertThat(second.getResponse().getContentAsString()).contains("event:chat-message");
		assertThat(third.getResponse().getContentAsString()).contains("event:chat-message");
	}
}
