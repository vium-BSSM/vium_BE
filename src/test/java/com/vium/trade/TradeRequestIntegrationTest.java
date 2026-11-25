package com.vium.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.auth.service.TokenService;
import com.vium.credit.service.CreditService;
import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.post.entity.Post;
import com.vium.post.entity.PostStatus;
import com.vium.post.entity.PostType;
import com.vium.post.repository.PostRepository;
import com.vium.post.service.PostExpiryService;
import com.vium.trade.dto.TradeResult;
import com.vium.trade.service.TradeChatNotifier;
import com.vium.trade.service.TradeService;
import com.vium.trade.service.TradeSystemEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:trade-request-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Sql(scripts = "/db/inventory-query-support.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class TradeRequestIntegrationTest {
	private static final Long BUYER = 42L;
	private static final Long SELLER = 43L;
	private static final Long OTHER = 44L;
	// UTC와 서울 날짜가 다를 때도 서울 기준 소비기한을 적용한다.
	private static final Instant NOW = Instant.parse("2026-11-16T16:00:00Z");
	private static final LocalDate TODAY = LocalDate.of(2026, 11, 17);

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired TokenService tokens;
	@Autowired TradeService trades;
	@Autowired CreditService credits;
	@Autowired PostRepository posts;
	@Autowired PostExpiryService expiry;
	@MockitoBean Clock clock;
	@MockitoBean TradeChatNotifier notifier;

	@BeforeEach
	void setUp() {
		when(clock.instant()).thenReturn(NOW);
		when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
		for (String table : List.of("credit_transactions", "trades", "user_blocks", "posts", "inventory_items",
				"users", "units", "storage_methods", "item_statuses")) {
			jdbc.update("delete from " + table);
		}
		jdbc.update("insert into units (id,code,name) values (1,'ea','개')");
		jdbc.update("insert into storage_methods (id,code,name) values (1,'cold','냉장')");
		jdbc.update("insert into item_statuses (id,code,name) values (1,'active','보유중')");
		for (long id : List.of(BUYER, SELLER, OTHER)) {
			jdbc.update("""
				insert into users(id,email,display_name,created_at,updated_at)
				values (?, ?, '테스트', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", id, id + "@example.com");
			jdbc.update("""
				insert into credit_transactions(user_id,type,amount,created_at)
				values (?, 'SIGNUP_REWARD', 10, CURRENT_TIMESTAMP)
				""", id);
		}
	}

	private Post createPost(PostType type, PostStatus status, LocalDate expiresOn) {
		jdbc.update("""
			insert into inventory_items(id,user_id,custom_name,status_id,unit_id,storage_method_id,
			initial_quantity,remaining_quantity,expires_on,created_at,updated_at)
			values (1,?,'우유',1,1,1,2,2,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
			""", SELLER, expiresOn);
		LocalDateTime now = LocalDateTime.now(clock);
		return posts.saveAndFlush(Post.builder().authorId(SELLER).inventoryItemId(1L).type(type).status(status)
			.title("우유").price(type == PostType.SALE ? 3 : 0).isOpened(false).latitude(37.5).longitude(127.0)
			.createdAt(now).updatedAt(now).build());
	}

	private String bearer(Long userId) { return "Bearer " + tokens.issue(userId).accessToken(); }
	private String path(Post post) { return "/api/posts/" + post.getId() + "/trades"; }
	private String postStatus(Post post) {
		return jdbc.queryForObject("select status from posts where id = ?", String.class, post.getId());
	}
	private long count(String table) { return jdbc.queryForObject("select count(*) from " + table, Long.class); }

	@Test
	void saleHoldsCreditAndKeepsPostOpen() throws Exception {
		Post post = createPost(PostType.SALE, PostStatus.OPEN, TODAY.plusDays(1));
		mvc.perform(post(path(post)).header("Authorization", bearer(BUYER)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.tradeId").isNumber()).andExpect(jsonPath("$.data.postId").value(post.getId()))
			.andExpect(jsonPath("$.data.status").value("REQUESTED"))
			.andExpect(jsonPath("$.data.postStatus").value("OPEN"))
			.andExpect(jsonPath("$.data.price").value(3)).andExpect(jsonPath("$.data.heldCredit").value(3));
		assertThat(credits.getAvailableBalance(BUYER)).isEqualTo(7);
		assertThat(credits.getHeldBalance(BUYER)).isEqualTo(3);
		assertThat(jdbc.queryForObject("select amount from credit_transactions where type='TRADE_HOLD'", Integer.class))
			.isEqualTo(-3);
		assertThat(postStatus(post)).isEqualTo("OPEN");
		verify(notifier).notify(any(), eq(TradeSystemEvent.TRADE_REQUESTED), any());
	}

	@Test
	void shareDoesNotChangeLedgerAndTodayIsNotExpired() throws Exception {
		Post post = createPost(PostType.SHARE, PostStatus.OPEN, TODAY);
		mvc.perform(post(path(post)).header("Authorization", bearer(BUYER))).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.price").value(0)).andExpect(jsonPath("$.data.heldCredit").value(0));
		assertThat(count("credit_transactions")).isEqualTo(3);
	}

	@Test
	void insufficientCreditRollsBackTradeInsert() throws Exception {
		Post post = createPost(PostType.SALE, PostStatus.OPEN, TODAY);
		jdbc.update("delete from credit_transactions where user_id = ?", BUYER);
		mvc.perform(post(path(post)).header("Authorization", bearer(BUYER))).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("INSUFFICIENT_CREDIT"));
		assertThat(count("trades")).isZero();
		assertThat(count("credit_transactions")).isEqualTo(2);
		assertThat(postStatus(post)).isEqualTo("OPEN");
	}

	@Test
	void ownPostIsRejectedBeforeExpiry() throws Exception {
		Post post = createPost(PostType.SHARE, PostStatus.OPEN, TODAY.minusDays(1));
		mvc.perform(post(path(post)).header("Authorization", bearer(SELLER))).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("CANNOT_TRADE_OWN_POST"));
		assertThat(postStatus(post)).isEqualTo("OPEN");
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void eitherBlockDirectionPreventsRequest(boolean buyerBlocks) throws Exception {
		Post post = createPost(PostType.SHARE, PostStatus.OPEN, TODAY);
		jdbc.update("insert into user_blocks(blocker_id,blocked_id,created_at) values (?,?,CURRENT_TIMESTAMP)",
			buyerBlocks ? BUYER : SELLER, buyerBlocks ? SELLER : BUYER);
		mvc.perform(post(path(post)).header("Authorization", bearer(BUYER))).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("USER_BLOCKED"));
		assertThat(count("trades")).isZero();
	}

	@Test
	void existingRequestRejectsSecondBuyer() throws Exception {
		Post post = createPost(PostType.SALE, PostStatus.OPEN, TODAY);
		trades.request(BUYER, post.getId());
		mvc.perform(post(path(post)).header("Authorization", bearer(OTHER))).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("TRADE_ALREADY_IN_PROGRESS"));
		assertThat(count("trades")).isEqualTo(1);
		assertThat(credits.getAvailableBalance(OTHER)).isEqualTo(10);
	}

	@Test
	void completedPostCannotBeRequested() throws Exception {
		Post post = createPost(PostType.SALE, PostStatus.COMPLETED, TODAY.minusDays(1));
		mvc.perform(post(path(post)).header("Authorization", bearer(BUYER))).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("POST_NOT_OPEN"));
	}

	@Test
	void expiredResponseCommitsPostExpiry() throws Exception {
		Post post = createPost(PostType.SALE, PostStatus.OPEN, TODAY.minusDays(1));
		mvc.perform(post(path(post)).header("Authorization", bearer(BUYER))).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("POST_EXPIRED"));
		assertThat(postStatus(post)).isEqualTo("EXPIRED");
		assertThat(count("trades")).isZero();
		assertThat(count("credit_transactions")).isEqualTo(3);
	}

	@Test
	void alreadyExpiredPostIsRejected() throws Exception {
		Post post = createPost(PostType.SHARE, PostStatus.EXPIRED, TODAY);
		mvc.perform(post(path(post)).header("Authorization", bearer(BUYER))).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("POST_EXPIRED"));
	}

	@Test
	void missingOrDeletedPostIsNotFound() throws Exception {
		mvc.perform(post("/api/posts/999/trades").header("Authorization", bearer(BUYER)))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"));
		Post post = createPost(PostType.SHARE, PostStatus.OPEN, TODAY);
		jdbc.update("update posts set deleted_at = CURRENT_TIMESTAMP where id = ?", post.getId());
		mvc.perform(post(path(post)).header("Authorization", bearer(BUYER)))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"));
	}

	@Test
	void authenticationIsRequired() throws Exception {
		mvc.perform(post("/api/posts/1/trades")).andExpect(status().isUnauthorized());
	}

	@Test
	void concurrentRequestsHoldOnlyOneBuyersCredit() throws Exception {
		Post post = createPost(PostType.SALE, PostStatus.OPEN, TODAY);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var futures = List.of(BUYER, OTHER).stream().map(buyer -> executor.submit(() -> {
				ready.countDown();
				if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
				try { trades.request(buyer, post.getId()); return "OK"; }
				catch (BusinessException e) { return e.getErrorCode().name(); }
			})).toList();
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			assertThat(List.of(futures.get(0).get(15, TimeUnit.SECONDS), futures.get(1).get(15, TimeUnit.SECONDS)))
				.containsExactlyInAnyOrder("OK", ErrorCode.TRADE_ALREADY_IN_PROGRESS.name());
		}
		assertThat(count("trades")).isEqualTo(1);
		assertThat(credits.getHeldBalance(BUYER) + credits.getHeldBalance(OTHER)).isEqualTo(3);
		assertThat(credits.getAvailableBalance(BUYER) + credits.getAvailableBalance(OTHER)).isEqualTo(17);
	}

	@Test
	void notificationFailureRollsBackTradeAndHold() throws Exception {
		Post post = createPost(PostType.SALE, PostStatus.OPEN, TODAY);
		doThrow(new IllegalStateException("notification failed")).when(notifier).notify(any(), any(), any());
		mvc.perform(post(path(post)).header("Authorization", bearer(BUYER))).andExpect(status().isInternalServerError());
		assertThat(count("trades")).isZero();
		assertThat(credits.getAvailableBalance(BUYER)).isEqualTo(10);
	}

	@Test
	void requestedTradePreventsPostDeletion() throws Exception {
		Post post = createPost(PostType.SALE, PostStatus.OPEN, TODAY);
		trades.request(BUYER, post.getId());
		mvc.perform(delete("/api/me/posts/" + post.getId()).header("Authorization", bearer(SELLER)))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("POST_LOCKED"));
		assertThat(jdbc.queryForObject("select deleted_at from posts where id=?", LocalDateTime.class, post.getId())).isNull();
		assertThat(credits.getHeldBalance(BUYER)).isEqualTo(3);
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void expiryFromBatchOrNewRequestRefundsExistingTrade(boolean batch) throws Exception {
		Post post = createPost(PostType.SALE, PostStatus.OPEN, TODAY);
		TradeResult trade = trades.request(BUYER, post.getId());
		jdbc.update("update inventory_items set expires_on = ? where id = 1", TODAY.minusDays(1));
		if (batch) {
			assertThat(expiry.expireOpenPosts()).isEqualTo(1);
			assertThat(expiry.expireOpenPosts()).isZero();
		} else {
			mvc.perform(post(path(post)).header("Authorization", bearer(OTHER))).andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("POST_EXPIRED"));
		}
		assertThat(postStatus(post)).isEqualTo("EXPIRED");
		assertThat(jdbc.queryForObject("select status from trades where id=?", String.class, trade.tradeId())).isEqualTo("CANCELED");
		assertThat(credits.getHeldBalance(BUYER)).isZero();
		assertThat(credits.getAvailableBalance(BUYER)).isEqualTo(10);
		assertThat(jdbc.queryForObject("select count(*) from credit_transactions where type='TRADE_REFUND'", Long.class)).isEqualTo(1);
		verify(notifier).notify(any(), eq(TradeSystemEvent.TRADE_CANCELED), any());
	}
}
