package com.vium.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.auth.service.TokenService;
import com.vium.chat.entity.ChatParticipant;
import com.vium.chat.entity.ChatRoom;
import com.vium.chat.repository.ChatParticipantRepository;
import com.vium.chat.repository.ChatRoomRepository;
import com.vium.chat.service.ChatMessageSentEvent;
import com.vium.notification.service.FcmPushClient;
import com.vium.notification.service.PushResult;
import com.vium.notification.service.PushTokenService;
import com.vium.trade.entity.Trade;
import com.vium.trade.service.TradeChatNotifier;
import com.vium.trade.service.TradeSystemEvent;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * AFTER_COMMIT 리스너는 실제 커밋이 있어야 실행되므로 이 클래스는 일부러 클래스 레벨 @Transactional을
 * 쓰지 않는다(기본 롤백 테스트에서는 커밋이 일어나지 않아 리스너가 호출되지 않는다). 대신 @BeforeEach에서
 * JdbcTemplate으로 직접 데이터를 정리·재구성해 테스트 간 데이터가 섞이지 않게 한다.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:chat-push-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Sql(scripts = "/db/auth-users.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class ChatMessagePushIntegrationTest {

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
	private ChatRoomRepository chatRoomRepository;

	@Autowired
	private ChatParticipantRepository chatParticipantRepository;

	@Autowired
	private TradeChatNotifier chatTradeNotifier;

	@Autowired
	private ApplicationEventPublisher eventPublisher;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@MockitoBean
	private FcmPushClient fcmPushClient;

	@MockitoSpyBean
	private PushTokenService pushTokenService;

	@BeforeEach
	void setUp() {
		jdbc.update("delete from chat_messages");
		jdbc.update("delete from chat_participants");
		jdbc.update("delete from chat_rooms");
		jdbc.update("delete from \"user_push_tokens\"");
		jdbc.update("delete from user_blocks");
		jdbc.update("delete from \"users\"");
		jdbc.update("""
			insert into "users" (id, email, display_name, created_at, updated_at)
			values (?, ?, '구매자', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
			       (?, ?, '판매자', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""", BUYER_ID, "buyer@example.com", SELLER_ID, "seller@example.com");

		reset(fcmPushClient, pushTokenService);
		when(fcmPushClient.send(anyString(), anyString(), anyString(), anyMap())).thenReturn(PushResult.SUCCESS);
	}

	private Long createRoom() {
		LocalDateTime now = LocalDateTime.now();
		ChatRoom room = chatRoomRepository.save(ChatRoom.create(POST_ID, BUYER_ID, SELLER_ID, now));
		chatParticipantRepository.save(ChatParticipant.create(room.getId(), BUYER_ID));
		chatParticipantRepository.save(ChatParticipant.create(room.getId(), SELLER_ID));
		return room.getId();
	}

	private void insertToken(Long userId, String token) {
		jdbc.update("""
			insert into "user_push_tokens" (user_id, token, platform, created_at, updated_at)
			values (?, ?, 'ANDROID', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""", userId, token);
	}

	private void setPushEnabled(Long userId, boolean enabled) {
		jdbc.update("update \"users\" set push_enabled = ? where id = ?", enabled, userId);
	}

	private void setWithdrawn(Long userId) {
		jdbc.update("update \"users\" set deleted_at = CURRENT_TIMESTAMP where id = ?", userId);
	}

	private boolean tokenExists(String token) {
		Integer count = jdbc.queryForObject(
			"select count(*) from \"user_push_tokens\" where token = ?", Integer.class, token);
		return count != null && count > 0;
	}

	private String bearer(Long userId) {
		return "Bearer " + tokenService.issue(userId).accessToken();
	}

	private void sendTextMessage(Long chatRoomId, Long asUserId, String content) throws Exception {
		mockMvc.perform(post("/api/me/chat-rooms/" + chatRoomId + "/messages").header("Authorization", bearer(asUserId))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"type\":\"TEXT\",\"content\":\"" + content + "\"}"))
			.andExpect(status().isOk());
	}

	@Test
	void sendsPushOnlyToRecipientNotSender() throws Exception {
		Long chatRoomId = createRoom();
		insertToken(BUYER_ID, "buyer-token");
		insertToken(SELLER_ID, "seller-token");

		sendTextMessage(chatRoomId, BUYER_ID, "안녕하세요");

		verify(fcmPushClient, timeout(2000)).send(eq("seller-token"), anyString(), anyString(), anyMap());
		verify(fcmPushClient, never()).send(eq("buyer-token"), anyString(), anyString(), anyMap());
	}

	@Test
	void sendsPushToAllRecipientDevices() throws Exception {
		Long chatRoomId = createRoom();
		insertToken(SELLER_ID, "seller-token-1");
		insertToken(SELLER_ID, "seller-token-2");

		sendTextMessage(chatRoomId, BUYER_ID, "안녕하세요");

		verify(fcmPushClient, timeout(2000)).send(eq("seller-token-1"), anyString(), anyString(), anyMap());
		verify(fcmPushClient, timeout(2000)).send(eq("seller-token-2"), anyString(), anyString(), anyMap());
	}

	@Test
	void skipsPushWhenRecipientPushDisabled() throws Exception {
		Long chatRoomId = createRoom();
		insertToken(SELLER_ID, "seller-token");
		setPushEnabled(SELLER_ID, false);

		sendTextMessage(chatRoomId, BUYER_ID, "안녕하세요");

		verify(fcmPushClient, after(300).never()).send(any(), any(), any(), any());
	}

	@Test
	void skipsPushWhenRecipientHasNoToken() throws Exception {
		Long chatRoomId = createRoom();

		sendTextMessage(chatRoomId, BUYER_ID, "안녕하세요");

		verify(fcmPushClient, after(300).never()).send(any(), any(), any(), any());
	}

	@Test
	void skipsPushWhenRecipientWithdrawn() throws Exception {
		Long chatRoomId = createRoom();
		insertToken(SELLER_ID, "seller-token");
		setWithdrawn(SELLER_ID);

		sendTextMessage(chatRoomId, BUYER_ID, "안녕하세요");

		verify(fcmPushClient, after(300).never()).send(any(), any(), any(), any());
	}

	@Test
	void skipsPushWhenSenderIsBlockedByRecipient() throws Exception {
		Long chatRoomId = createRoom();
		insertToken(SELLER_ID, "seller-token");
		jdbc.update("insert into user_blocks (blocker_id, blocked_id, created_at) values (?, ?, CURRENT_TIMESTAMP)",
			SELLER_ID, BUYER_ID);

		mockMvc.perform(post("/api/me/chat-rooms/" + chatRoomId + "/messages").header("Authorization", bearer(BUYER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"type\":\"TEXT\",\"content\":\"안녕하세요\"}"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("USER_BLOCKED"));

		verify(fcmPushClient, after(300).never()).send(any(), any(), any(), any());
	}

	@Test
	void messageSavedAndApiOkWhenPushReturnsFailed() throws Exception {
		Long chatRoomId = createRoom();
		insertToken(SELLER_ID, "seller-token");
		when(fcmPushClient.send(anyString(), anyString(), anyString(), anyMap())).thenReturn(PushResult.FAILED);

		sendTextMessage(chatRoomId, BUYER_ID, "안녕하세요");

		verify(fcmPushClient, timeout(2000)).send(eq("seller-token"), anyString(), anyString(), anyMap());
		Integer messageCount = jdbc.queryForObject(
			"select count(*) from chat_messages where chat_room_id = ?", Integer.class, chatRoomId);
		assertThat(messageCount).isEqualTo(1);
	}

	@Test
	void messageSavedAndApiOkWhenPushThrows() throws Exception {
		Long chatRoomId = createRoom();
		insertToken(SELLER_ID, "seller-token");
		when(fcmPushClient.send(anyString(), anyString(), anyString(), anyMap()))
			.thenThrow(new RuntimeException("fcm down"));

		sendTextMessage(chatRoomId, BUYER_ID, "안녕하세요");

		verify(fcmPushClient, timeout(2000)).send(eq("seller-token"), anyString(), anyString(), anyMap());
		Integer messageCount = jdbc.queryForObject(
			"select count(*) from chat_messages where chat_room_id = ?", Integer.class, chatRoomId);
		assertThat(messageCount).isEqualTo(1);
	}

	@Test
	void deletesInvalidToken() throws Exception {
		Long chatRoomId = createRoom();
		insertToken(SELLER_ID, "seller-token");
		when(fcmPushClient.send(anyString(), anyString(), anyString(), anyMap())).thenReturn(PushResult.INVALID_TOKEN);

		sendTextMessage(chatRoomId, BUYER_ID, "안녕하세요");

		verify(pushTokenService, timeout(2000)).deleteByToken("seller-token");
		awaitTokenDeleted("seller-token");
	}

	/**
	 * Mockito의 verify(timeout())는 스파이 호출이 "기록"되는 시점에 반환될 수 있어, 같은 메서드 안의
	 * DB 삭제(트랜잭션 커밋)가 아직 끝나지 않았을 수 있다. 별도 의존성 없이 짧게 폴링해 확인한다.
	 */
	private void awaitTokenDeleted(String token) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 2000;
		while (tokenExists(token) && System.currentTimeMillis() < deadline) {
			Thread.sleep(20);
		}
		assertThat(tokenExists(token)).isFalse();
	}

	@Test
	void pushTitleBodyAndDataContainNoMessageContent() throws Exception {
		Long chatRoomId = createRoom();
		insertToken(SELLER_ID, "seller-token");

		sendTextMessage(chatRoomId, BUYER_ID, "이체 계좌번호는 1234입니다");

		@SuppressWarnings("unchecked")
		org.mockito.ArgumentCaptor<Map<String, String>> dataCaptor = org.mockito.ArgumentCaptor.forClass(Map.class);
		verify(fcmPushClient, timeout(2000)).send(eq("seller-token"), eq("새 메시지"), eq("새 메시지가 도착했어요"),
			dataCaptor.capture());
		assertThat(dataCaptor.getValue()).isEqualTo(Map.of("type", "chat", "chatRoomId", String.valueOf(chatRoomId)));
	}

	@Test
	void systemMessageFromTradeNotifierDoesNotTriggerPush() {
		insertToken(SELLER_ID, "seller-token");
		insertToken(BUYER_ID, "buyer-token");
		Trade trade = Trade.request(POST_ID, BUYER_ID, SELLER_ID, 1000, LocalDateTime.now());

		chatTradeNotifier.notify(trade, TradeSystemEvent.TRADE_REQUESTED, BUYER_ID);

		verify(fcmPushClient, after(300).never()).send(any(), any(), any(), any());
	}

	@Test
	void eventIsDiscardedWhenTransactionRollsBack() {
		insertToken(SELLER_ID, "seller-token");
		TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

		transactionTemplate.execute(status -> {
			eventPublisher.publishEvent(new ChatMessageSentEvent(999L, SELLER_ID));
			status.setRollbackOnly();
			return null;
		});

		verify(fcmPushClient, after(300).never()).send(any(), any(), any(), any());
	}

	@Test
	void eventIsDispatchedAfterCommit() {
		insertToken(SELLER_ID, "seller-token");
		TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

		transactionTemplate.execute(status -> {
			eventPublisher.publishEvent(new ChatMessageSentEvent(999L, SELLER_ID));
			return null;
		});

		verify(fcmPushClient, timeout(2000)).send(eq("seller-token"), anyString(), anyString(), anyMap());
	}
}
