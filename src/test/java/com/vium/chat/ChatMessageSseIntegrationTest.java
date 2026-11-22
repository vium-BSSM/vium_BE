package com.vium.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.auth.service.TokenService;
import com.vium.chat.repository.ChatParticipantRepository;
import com.vium.chat.repository.ChatRoomRepository;
import com.vium.chat.entity.ChatParticipant;
import com.vium.chat.entity.ChatRoom;
import com.vium.chat.service.ChatMessageSseEvent;
import com.vium.notification.service.FcmPushClient;
import com.vium.notification.service.PushResult;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import com.vium.notification.sse.SseConnectionRegistry;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * GET /api/me/events 전체 흐름 검증. AFTER_COMMIT 리스너는 실제 커밋이 있어야 실행되므로
 * ChatMessagePushIntegrationTest와 같은 이유로 클래스 레벨 @Transactional을 쓰지 않는다.
 * 각 테스트가 연 SSE 연결은 @AfterEach에서 레지스트리를 통째로 정리해 테스트 간 간섭을 막는다.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:chat-sse-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Sql(scripts = "/db/auth-users.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class ChatMessageSseIntegrationTest {

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
	private ApplicationEventPublisher eventPublisher;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private SseConnectionRegistry registry;

	@MockitoBean
	private FcmPushClient fcmPushClient;

	@BeforeEach
	void setUp() {
		jdbc.update("delete from chat_messages");
		jdbc.update("delete from chat_participants");
		jdbc.update("delete from chat_rooms");
		jdbc.update("delete from \"user_push_tokens\"");
		jdbc.update("delete from \"users\"");
		jdbc.update("""
			insert into "users" (id, email, display_name, created_at, updated_at)
			values (?, ?, '구매자', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
			       (?, ?, '판매자', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""", BUYER_ID, "buyer@example.com", SELLER_ID, "seller@example.com");

		reset(fcmPushClient);
		when(fcmPushClient.send(anyString(), anyString(), anyString(), anyMap())).thenReturn(PushResult.SUCCESS);
	}

	@AfterEach
	void tearDown() {
		registry.shutdown();
	}

	private Long createRoom() {
		LocalDateTime now = LocalDateTime.now();
		ChatRoom room = chatRoomRepository.save(ChatRoom.create(POST_ID, BUYER_ID, SELLER_ID, now));
		chatParticipantRepository.save(ChatParticipant.create(room.getId(), BUYER_ID));
		chatParticipantRepository.save(ChatParticipant.create(room.getId(), SELLER_ID));
		return room.getId();
	}

	private String bearer(Long userId) {
		return "Bearer " + tokenService.issue(userId).accessToken();
	}

	private MvcResult connect(Long userId) throws Exception {
		return mockMvc.perform(get("/api/me/events").header("Authorization", bearer(userId)))
			.andExpect(request().asyncStarted())
			.andReturn();
	}

	private void sendTextMessage(Long chatRoomId, Long asUserId, String content) throws Exception {
		mockMvc.perform(post("/api/me/chat-rooms/" + chatRoomId + "/messages").header("Authorization", bearer(asUserId))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"type\":\"TEXT\",\"content\":\"" + content + "\"}"))
			.andExpect(status().isOk());
	}

	private void awaitContains(MvcResult result, String needle) throws Exception {
		long deadline = System.currentTimeMillis() + 2000;
		while (System.currentTimeMillis() < deadline) {
			String content = result.getResponse().getContentAsString();
			if (content.contains(needle)) {
				return;
			}
			Thread.sleep(20);
		}
		assertThat(result.getResponse().getContentAsString()).contains(needle);
	}

	@Test
	void unauthenticatedConnectIsRejected() throws Exception {
		mockMvc.perform(get("/api/me/events")).andExpect(status().isUnauthorized());
	}

	@Test
	void connectReceivesPingImmediately() throws Exception {
		MvcResult connection = connect(BUYER_ID);
		awaitContains(connection, "event:ping");
	}

	@Test
	void recipientReceivesEventSenderDoesNot() throws Exception {
		Long chatRoomId = createRoom();
		MvcResult sellerConnection = connect(SELLER_ID);
		MvcResult buyerConnection = connect(BUYER_ID);
		awaitContains(sellerConnection, "event:ping");
		awaitContains(buyerConnection, "event:ping");

		sendTextMessage(chatRoomId, BUYER_ID, "안녕하세요");

		awaitContains(sellerConnection, "event:chat-message");
		assertThat(buyerConnection.getResponse().getContentAsString()).doesNotContain("event:chat-message");
	}

	@Test
	void eventPayloadContainsNoMessageContent() throws Exception {
		Long chatRoomId = createRoom();
		MvcResult sellerConnection = connect(SELLER_ID);
		awaitContains(sellerConnection, "event:ping");

		sendTextMessage(chatRoomId, BUYER_ID, "이체 계좌번호는 1234입니다");

		awaitContains(sellerConnection, "event:chat-message");
		String content = sellerConnection.getResponse().getContentAsString();
		assertThat(content).doesNotContain("이체 계좌번호는 1234입니다");
		assertThat(content).contains("chatRoomId").contains("messageId");
	}

	@Test
	void eventIsDiscardedWhenTransactionRollsBack() throws Exception {
		MvcResult sellerConnection = connect(SELLER_ID);
		awaitContains(sellerConnection, "event:ping");
		TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

		transactionTemplate.execute(status -> {
			eventPublisher.publishEvent(new ChatMessageSseEvent(999L, SELLER_ID, 1L));
			status.setRollbackOnly();
			return null;
		});

		Thread.sleep(300);
		assertThat(sellerConnection.getResponse().getContentAsString()).doesNotContain("event:chat-message");
	}

	@Test
	void eventIsDispatchedAfterCommit() throws Exception {
		MvcResult sellerConnection = connect(SELLER_ID);
		awaitContains(sellerConnection, "event:ping");
		TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

		transactionTemplate.execute(status -> {
			eventPublisher.publishEvent(new ChatMessageSseEvent(999L, SELLER_ID, 1L));
			return null;
		});

		awaitContains(sellerConnection, "event:chat-message");
	}

	@Test
	void sseAndFcmPushBothFireIndependentlyForSameMessage() throws Exception {
		Long chatRoomId = createRoom();
		jdbc.update("""
			insert into "user_push_tokens" (user_id, token, platform, created_at, updated_at)
			values (?, 'seller-token', 'ANDROID', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""", SELLER_ID);
		MvcResult sellerConnection = connect(SELLER_ID);
		awaitContains(sellerConnection, "event:ping");

		sendTextMessage(chatRoomId, BUYER_ID, "안녕하세요");

		awaitContains(sellerConnection, "event:chat-message");
		verify(fcmPushClient, timeout(2000)).send(org.mockito.ArgumentMatchers.eq("seller-token"), anyString(),
			anyString(), anyMap());
	}
}
