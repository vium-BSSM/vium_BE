package com.vium.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import com.vium.auth.service.TokenService;
import com.vium.trade.entity.Trade;
import com.vium.trade.service.TradeChatNotifier;
import com.vium.trade.service.TradeSystemEvent;
import java.time.LocalDateTime;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ChatTradeNotifier(SYSTEM 메시지)가 buyer/seller 모두에게 SSE 이벤트를 보내는지 확인한다.
 * notify()는 호출자 트랜잭션에 합류하는 구조라, 실제 TradeService 호출 경로와 같게 TransactionTemplate으로
 * 감싸 커밋 후에만 리스너가 실행되는 조건을 재현한다(감싸지 않으면 @TransactionalEventListener(AFTER_COMMIT)가
 * 트랜잭션 없음으로 간주해 이벤트를 버린다).
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:chat-trade-sse-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Sql(scripts = "/db/auth-users.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class ChatTradeNotifierSseIntegrationTest {

	private static final Long BUYER_ID = 42L;
	private static final Long SELLER_ID = 43L;
	private static final Long POST_ID = 100L;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TokenService tokenService;

	@Autowired
	private TradeChatNotifier chatTradeNotifier;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private SseConnectionRegistry registry;

	@BeforeEach
	void setUp() {
		jdbc.update("delete from chat_messages");
		jdbc.update("delete from chat_participants");
		jdbc.update("delete from chat_rooms");
		jdbc.update("delete from \"users\"");
		jdbc.update("""
			insert into "users" (id, email, display_name, created_at, updated_at)
			values (?, ?, '구매자', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
			       (?, ?, '판매자', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""", BUYER_ID, "buyer@example.com", SELLER_ID, "seller@example.com");
	}

	@AfterEach
	void tearDown() {
		registry.shutdown();
	}

	private MvcResult connect(Long userId) throws Exception {
		return mockMvc.perform(get("/api/me/events")
				.header("Authorization", "Bearer " + tokenService.issue(userId).accessToken()))
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
		assertThat(result.getResponse().getContentAsString()).contains(needle);
	}

	@Test
	void systemMessageReachesBothBuyerAndSeller() throws Exception {
		MvcResult buyerConnection = connect(BUYER_ID);
		MvcResult sellerConnection = connect(SELLER_ID);
		awaitContains(buyerConnection, "event:ping");
		awaitContains(sellerConnection, "event:ping");

		Trade trade = Trade.request(POST_ID, BUYER_ID, SELLER_ID, 1000, LocalDateTime.now());
		TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
		transactionTemplate.execute(status -> {
			chatTradeNotifier.notify(trade, TradeSystemEvent.TRADE_REQUESTED);
			return null;
		});

		awaitContains(buyerConnection, "event:chat-message");
		awaitContains(sellerConnection, "event:chat-message");
	}
}
