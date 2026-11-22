package com.vium.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import com.vium.auth.service.TokenService;
import com.vium.notification.sse.SseConnectionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * heartbeat 주기(1초)와 최대 연결 수명(2초)을 짧게 설정한 전용 컨텍스트. 실제 25초/30분을 기다리지
 * 않고 바운드 있는 폴링(Awaitility 없이)으로 빠르게 검증한다.
 */
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:sse-heartbeat-maxage-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
	"sse.heartbeat-interval-seconds=1",
	"sse.max-connection-age-seconds=2"
})
@AutoConfigureMockMvc
@Sql(scripts = "/db/auth-users.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class SseHeartbeatAndMaxAgeIntegrationTest {

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

	private String content(MvcResult result) {
		try {
			return result.getResponse().getContentAsString();
		} catch (java.io.UnsupportedEncodingException e) {
			throw new IllegalStateException(e);
		}
	}

	private int countOccurrences(String content, String needle) {
		int count = 0;
		int index = 0;
		while ((index = content.indexOf(needle, index)) != -1) {
			count++;
			index += needle.length();
		}
		return count;
	}

	private void awaitTrue(java.util.function.BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMillis;
		while (System.currentTimeMillis() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			Thread.sleep(20);
		}
	}

	@Test
	void heartbeatSendsPingPeriodically() throws Exception {
		MvcResult connection = connect();

		awaitTrue(() -> countOccurrences(content(connection), "event:ping") >= 3, 4000);

		assertThat(countOccurrences(content(connection), "event:ping")).isGreaterThanOrEqualTo(3);
	}

	@Test
	void connectionIsClosedAfterMaxAge() throws Exception {
		MvcResult connection = connect();

		// 최대 수명(2초)을 넘기고 heartbeat 틱(1초 간격)이 적어도 한 번은 더 돈 뒤 종료됐는지 확인한다.
		Thread.sleep(3500);
		registry.broadcast(USER_ID, "chat-message", "probe-after-max-age");
		Thread.sleep(300);

		assertThat(connection.getResponse().getContentAsString()).doesNotContain("probe-after-max-age");
	}
}
