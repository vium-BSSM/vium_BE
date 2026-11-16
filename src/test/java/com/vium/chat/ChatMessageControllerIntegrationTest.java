package com.vium.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.auth.service.TokenService;
import com.vium.chat.entity.ChatParticipant;
import com.vium.chat.entity.ChatRoom;
import com.vium.chat.repository.ChatParticipantRepository;
import com.vium.chat.repository.ChatRoomRepository;
import com.vium.image.dto.ImageAccessResponse;
import com.vium.image.service.ImageStorage;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:chat-message-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Transactional
class ChatMessageControllerIntegrationTest {

	private static final Long BUYER_ID = 42L;
	private static final Long SELLER_ID = 43L;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TokenService tokenService;

	@Autowired
	private ChatRoomRepository chatRoomRepository;

	@Autowired
	private ChatParticipantRepository chatParticipantRepository;

	@Autowired
	private Clock clock;

	@Autowired
	private EntityManager entityManager;

	@MockitoBean
	private ImageStorage imageStorage;

	private long nextImageId = 1;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("delete from user_blocks");
	}

	private Long createRoom(Long buyerId, Long sellerId) {
		LocalDateTime now = LocalDateTime.now(clock);
		ChatRoom room = chatRoomRepository.save(ChatRoom.create(100L, buyerId, sellerId, now));
		chatParticipantRepository.save(ChatParticipant.create(room.getId(), buyerId));
		chatParticipantRepository.save(ChatParticipant.create(room.getId(), sellerId));
		return room.getId();
	}

	private Long insertChatImage(Long userId) {
		long id = nextImageId++;
		jdbcTemplate.update("""
			insert into uploaded_images (id, user_id, purpose, bucket, object_key, content_type, size_bytes, ready, created_at)
			values (?, ?, 'CHAT', 'private', ?, 'image/jpeg', 100, true, CURRENT_TIMESTAMP)
			""", id, userId, "images/" + userId + "/" + id + ".jpg");
		return id;
	}

	private String bearer(Long userId) {
		return "Bearer " + tokenService.issue(userId).accessToken();
	}

	@Test
	void sendsTextMessageAndSenderSeesIsMineTrue() throws Exception {
		Long chatRoomId = createRoom(BUYER_ID, SELLER_ID);

		mockMvc.perform(post("/api/me/chat-rooms/" + chatRoomId + "/messages").header("Authorization", bearer(BUYER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"type\":\"TEXT\",\"content\":\"내일 저녁에 가능할까요?\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.type").value("TEXT"))
			.andExpect(jsonPath("$.data.content").value("내일 저녁에 가능할까요?"))
			.andExpect(jsonPath("$.data.senderId").value(BUYER_ID))
			.andExpect(jsonPath("$.data.isMine").value(true));
	}

	@Test
	void recipientHistorySeesIsMineFalseAndMarksRead() throws Exception {
		Long chatRoomId = createRoom(BUYER_ID, SELLER_ID);
		mockMvc.perform(post("/api/me/chat-rooms/" + chatRoomId + "/messages").header("Authorization", bearer(BUYER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"type\":\"TEXT\",\"content\":\"안녕하세요\"}"))
			.andExpect(status().isOk());

		mockMvc.perform(get("/api/me/chat-rooms/" + chatRoomId + "/messages").header("Authorization", bearer(SELLER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.messages.length()").value(1))
			.andExpect(jsonPath("$.data.messages[0].isMine").value(false))
			.andExpect(jsonPath("$.data.hasNext").value(false));
		entityManager.flush();

		Long lastReadMessageId = jdbcTemplate.queryForObject(
			"select last_read_message_id from chat_participants where chat_room_id = ? and user_id = ?", Long.class,
			chatRoomId, SELLER_ID);
		Long latestMessageId = jdbcTemplate.queryForObject(
			"select max(id) from chat_messages where chat_room_id = ?", Long.class, chatRoomId);
		assertThat(lastReadMessageId).isEqualTo(latestMessageId);
	}

	@Test
	void historyPaginatesWithMessageIdCursor() throws Exception {
		Long chatRoomId = createRoom(BUYER_ID, SELLER_ID);
		for (int i = 0; i < 5; i++) {
			mockMvc.perform(post("/api/me/chat-rooms/" + chatRoomId + "/messages")
					.header("Authorization", bearer(BUYER_ID)).contentType(MediaType.APPLICATION_JSON)
					.content("{\"type\":\"TEXT\",\"content\":\"메시지" + i + "\"}"))
				.andExpect(status().isOk());
		}

		var firstPage = mockMvc.perform(get("/api/me/chat-rooms/" + chatRoomId + "/messages").param("size", "2")
				.header("Authorization", bearer(SELLER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.messages.length()").value(2))
			.andExpect(jsonPath("$.data.hasNext").value(true))
			.andReturn();

		String nextCursor = com.jayway.jsonpath.JsonPath.read(firstPage.getResponse().getContentAsString(),
			"$.data.nextCursor");

		mockMvc.perform(get("/api/me/chat-rooms/" + chatRoomId + "/messages").param("size", "2")
				.param("cursor", nextCursor).header("Authorization", bearer(SELLER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.messages.length()").value(2))
			.andExpect(jsonPath("$.data.hasNext").value(true));
	}

	@Test
	void sendRejectsBlockedRelationship() throws Exception {
		Long chatRoomId = createRoom(BUYER_ID, SELLER_ID);
		jdbcTemplate.update(
			"insert into user_blocks (blocker_id, blocked_id, created_at) values (?, ?, CURRENT_TIMESTAMP)",
			SELLER_ID, BUYER_ID);

		mockMvc.perform(post("/api/me/chat-rooms/" + chatRoomId + "/messages").header("Authorization", bearer(BUYER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"type\":\"TEXT\",\"content\":\"안녕하세요\"}"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("USER_BLOCKED"));
	}

	@Test
	void sendRejectsEmptyTextContent() throws Exception {
		Long chatRoomId = createRoom(BUYER_ID, SELLER_ID);

		mockMvc.perform(post("/api/me/chat-rooms/" + chatRoomId + "/messages").header("Authorization", bearer(BUYER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"type\":\"TEXT\",\"content\":\"\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	void sendRejectsImageNotOwnedByCaller() throws Exception {
		Long chatRoomId = createRoom(BUYER_ID, SELLER_ID);
		Long othersImageId = insertChatImage(SELLER_ID);

		mockMvc.perform(post("/api/me/chat-rooms/" + chatRoomId + "/messages").header("Authorization", bearer(BUYER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"type\":\"IMAGE\",\"imageId\":" + othersImageId + "}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	void sendsImageMessageAndRecipientReceivesSignedUrl() throws Exception {
		Long chatRoomId = createRoom(BUYER_ID, SELLER_ID);
		Long imageId = insertChatImage(BUYER_ID);
		when(imageStorage.access(any())).thenReturn(
			new ImageAccessResponse(imageId, "https://signed.example.com/chat.jpg", Instant.now()));

		mockMvc.perform(post("/api/me/chat-rooms/" + chatRoomId + "/messages").header("Authorization", bearer(BUYER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"type\":\"IMAGE\",\"imageId\":" + imageId + "}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.type").value("IMAGE"))
			.andExpect(jsonPath("$.data.content").doesNotExist())
			.andExpect(jsonPath("$.data.imageUrl").value("https://signed.example.com/chat.jpg"));

		mockMvc.perform(get("/api/me/chat-rooms/" + chatRoomId + "/messages").header("Authorization", bearer(SELLER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.messages[0].imageUrl").value("https://signed.example.com/chat.jpg"));
	}

	@Test
	void sendRejectsForNonParticipant() throws Exception {
		Long chatRoomId = createRoom(BUYER_ID, SELLER_ID);

		mockMvc.perform(post("/api/me/chat-rooms/" + chatRoomId + "/messages").header("Authorization", bearer(44L))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"type\":\"TEXT\",\"content\":\"안녕하세요\"}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("CHAT_ROOM_NOT_FOUND"));
	}

	@Test
	void historyAllowedForParticipantWhoHasLeft() throws Exception {
		Long chatRoomId = createRoom(BUYER_ID, SELLER_ID);
		mockMvc.perform(post("/api/me/chat-rooms/" + chatRoomId + "/messages").header("Authorization", bearer(BUYER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"type\":\"TEXT\",\"content\":\"안녕하세요\"}"))
			.andExpect(status().isOk());

		jdbcTemplate.update(
			"update chat_participants set left_at = CURRENT_TIMESTAMP where chat_room_id = ? and user_id = ?",
			chatRoomId, SELLER_ID);

		mockMvc.perform(get("/api/me/chat-rooms/" + chatRoomId + "/messages").header("Authorization", bearer(SELLER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.messages.length()").value(1));
	}

	@Test
	void sendingRestoresPartnerWhoHadLeft() throws Exception {
		Long chatRoomId = createRoom(BUYER_ID, SELLER_ID);
		jdbcTemplate.update(
			"update chat_participants set left_at = CURRENT_TIMESTAMP where chat_room_id = ? and user_id = ?",
			chatRoomId, SELLER_ID);

		mockMvc.perform(post("/api/me/chat-rooms/" + chatRoomId + "/messages").header("Authorization", bearer(BUYER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"type\":\"TEXT\",\"content\":\"안녕하세요\"}"))
			.andExpect(status().isOk());
		entityManager.flush();

		Object leftAt = jdbcTemplate.queryForObject(
			"select left_at from chat_participants where chat_room_id = ? and user_id = ?", Object.class, chatRoomId,
			SELLER_ID);
		assertThat(leftAt).isNull();
	}
}
