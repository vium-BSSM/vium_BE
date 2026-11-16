package com.vium.credit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.auth.service.TokenService;
import com.vium.credit.repository.CreditTransactionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:credit-controller-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Sql(scripts = "/db/auth-users.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class CreditControllerIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-11-16T03:00:00Z");
	private static final Long SELF_ID = 42L;
	private static final Long OTHER_ID = 43L;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TokenService tokenService;

	@MockitoBean
	private Clock clock;

	@MockitoSpyBean
	private CreditTransactionRepository creditTransactionRepository;

	private String bearer;

	@BeforeEach
	void setUp() {
		when(clock.instant()).thenReturn(NOW);
		when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));

		jdbcTemplate.update("delete from credit_transactions");
		jdbcTemplate.update("delete from trades");
		jdbcTemplate.update("delete from users");
		jdbcTemplate.update("""
			insert into users (id, email, display_name, password_hash, created_at, updated_at, deleted_at)
			values (42, 'self@example.com', 'Self', null, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, null),
			       (43, 'other@example.com', 'Other', null, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, null)
			""");
		bearer = "Bearer " + tokenService.issue(SELF_ID).accessToken();
	}

	private void seedTransaction(Long userId, String type, int amount) {
		jdbcTemplate.update("""
			insert into credit_transactions (user_id, type, amount, created_at) values (?, ?, ?, CURRENT_TIMESTAMP)
			""", userId, type, amount);
	}

	private void seedTrade(long id, Long buyerId, String status, int price) {
		jdbcTemplate.update("""
			insert into trades (id, post_id, buyer_id, seller_id, status, price, requested_at)
			values (?, 1, ?, 1, ?, ?, CURRENT_TIMESTAMP)
			""", id, buyerId, status, price);
	}

	@Test
	void returnsAvailableAndHeldBalance() throws Exception {
		seedTransaction(SELF_ID, "TRADE_REFUND", 1000);
		seedTransaction(SELF_ID, "TRADE_HOLD", -200);
		seedTrade(1, SELF_ID, "REQUESTED", 300);
		seedTrade(2, SELF_ID, "ACCEPTED", 150);
		seedTrade(3, SELF_ID, "COMPLETED", 9999);
		seedTrade(4, OTHER_ID, "REQUESTED", 7777);

		mockMvc.perform(get("/api/me/credits").header("Authorization", bearer))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.availableBalance").value(800))
			.andExpect(jsonPath("$.data.heldBalance").value(450));
	}

	@Test
	void listsTransactionsWithCursorPaginationNewestFirst() throws Exception {
		seedTransaction(SELF_ID, "TRADE_REFUND", 100);
		seedTransaction(SELF_ID, "TRADE_REFUND", 200);
		seedTransaction(SELF_ID, "TRADE_HOLD", -50);
		seedTransaction(OTHER_ID, "TRADE_REFUND", 999);

		String firstPage = mockMvc.perform(get("/api/me/credits/transactions")
				.header("Authorization", bearer).param("size", "2"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.transactions.length()").value(2))
			.andExpect(jsonPath("$.data.hasNext").value(true))
			.andExpect(jsonPath("$.data.transactions[0].amount").value(-50))
			.andExpect(jsonPath("$.data.transactions[1].amount").value(200))
			.andReturn().getResponse().getContentAsString();

		com.jayway.jsonpath.DocumentContext json = com.jayway.jsonpath.JsonPath.parse(firstPage);
		Integer nextCursor = json.read("$.data.nextCursor");

		mockMvc.perform(get("/api/me/credits/transactions")
				.header("Authorization", bearer).param("size", "2").param("cursor", String.valueOf(nextCursor)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.transactions.length()").value(1))
			.andExpect(jsonPath("$.data.hasNext").value(false))
			.andExpect(jsonPath("$.data.transactions[0].amount").value(100));
	}

	@Test
	void filtersTransactionsByType() throws Exception {
		seedTransaction(SELF_ID, "TRADE_REFUND", 100);
		seedTransaction(SELF_ID, "TRADE_HOLD", -50);

		mockMvc.perform(get("/api/me/credits/transactions")
				.header("Authorization", bearer).param("type", "TRADE_HOLD"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.transactions.length()").value(1))
			.andExpect(jsonPath("$.data.transactions[0].type").value("TRADE_HOLD"));
	}

	@Test
	void rejectsOutOfRangeSize() throws Exception {
		mockMvc.perform(get("/api/me/credits/transactions").header("Authorization", bearer).param("size", "51"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
		mockMvc.perform(get("/api/me/credits/transactions").header("Authorization", bearer).param("size", "0"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	void rejectsInvalidTypeParam() throws Exception {
		mockMvc.perform(get("/api/me/credits/transactions").header("Authorization", bearer).param("type", "NOT_A_TYPE"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	void claimsDailyRewardOnceThenRejectsSameDay() throws Exception {
		mockMvc.perform(post("/api/me/credits/daily-reward").header("Authorization", bearer))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.amount").value(1))
			.andExpect(jsonPath("$.data.availableBalance").value(1));

		mockMvc.perform(post("/api/me/credits/daily-reward").header("Authorization", bearer))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("DAILY_REWARD_ALREADY_CLAIMED"));
	}

	@Test
	void convertsConcurrentDailyRewardRaceToConflict() throws Exception {
		doThrow(new DataIntegrityViolationException("simulated unique violation"))
			.when(creditTransactionRepository).saveAndFlush(any());

		mockMvc.perform(post("/api/me/credits/daily-reward").header("Authorization", bearer))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("DAILY_REWARD_ALREADY_CLAIMED"));

		Integer count = jdbcTemplate.queryForObject(
			"select count(*) from credit_transactions where user_id = 42", Integer.class);
		org.assertj.core.api.Assertions.assertThat(count).isZero();
	}
}
