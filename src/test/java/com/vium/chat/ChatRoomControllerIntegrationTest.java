package com.vium.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.auth.service.TokenService;
import com.vium.post.entity.Post;
import com.vium.post.entity.PostStatus;
import com.vium.post.entity.PostType;
import com.vium.post.repository.PostRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:chat-room-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Transactional
@Sql(scripts = "/db/auth-users.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class ChatRoomControllerIntegrationTest {

	private static final Long BUYER_ID = 42L;
	private static final Long SELLER_ID = 43L;
	private static final Long OTHER_BUYER_ID = 44L;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TokenService tokenService;

	@Autowired
	private PostRepository postRepository;

	@Autowired
	private Clock clock;

	@Autowired
	private EntityManager entityManager;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("delete from user_blocks");
		jdbcTemplate.update("delete from users");
		jdbcTemplate.update("""
			insert into users (id, email, display_name, created_at, updated_at, deleted_at)
			values (42, 'buyer@example.com', '구매자', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, null),
			       (43, 'seller@example.com', '판매자', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, null),
			       (44, 'other@example.com', '다른구매자', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, null)
			""");
	}

	private Post createPost(Long authorId) {
		LocalDateTime now = LocalDateTime.now(clock);
		Post post = Post.builder()
			.authorId(authorId)
			.inventoryItemId(1L)
			.type(PostType.SHARE)
			.status(PostStatus.OPEN)
			.title("우유 1L 팔아요")
			.description("설명")
			.price(0)
			.isOpened(true)
			.latitude(37.5)
			.longitude(127.0)
			.createdAt(now)
			.updatedAt(now)
			.build();
		return postRepository.save(post);
	}

	private String bearer(Long userId) {
		return "Bearer " + tokenService.issue(userId).accessToken();
	}

	@Test
	void createsNewRoomThenIsIdempotent() throws Exception {
		Post post = createPost(SELLER_ID);

		mockMvc.perform(post("/api/posts/" + post.getId() + "/chat-rooms").header("Authorization", bearer(BUYER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.created").value(true));

		Long chatRoomId = jdbcTemplate.queryForObject(
			"select id from chat_rooms where post_id = ? and buyer_id = ?", Long.class, post.getId(), BUYER_ID);

		mockMvc.perform(post("/api/posts/" + post.getId() + "/chat-rooms").header("Authorization", bearer(BUYER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.created").value(false))
			.andExpect(jsonPath("$.data.chatRoomId").value(chatRoomId));

		Integer roomCount = jdbcTemplate.queryForObject(
			"select count(*) from chat_rooms where post_id = ? and buyer_id = ?", Integer.class, post.getId(),
			BUYER_ID);
		assertThat(roomCount).isEqualTo(1);
	}

	@Test
	void rejoinsAfterLeaving() throws Exception {
		Post post = createPost(SELLER_ID);
		mockMvc.perform(post("/api/posts/" + post.getId() + "/chat-rooms").header("Authorization", bearer(BUYER_ID)))
			.andExpect(status().isOk());
		Long chatRoomId = jdbcTemplate.queryForObject(
			"select id from chat_rooms where post_id = ? and buyer_id = ?", Long.class, post.getId(), BUYER_ID);

		mockMvc.perform(delete("/api/me/chat-rooms/" + chatRoomId).header("Authorization", bearer(BUYER_ID)))
			.andExpect(status().isOk());
		entityManager.flush();
		Object leftAt = jdbcTemplate.queryForObject(
			"select left_at from chat_participants where chat_room_id = ? and user_id = ?", Object.class, chatRoomId,
			BUYER_ID);
		assertThat(leftAt).isNotNull();

		mockMvc.perform(get("/api/me/chat-rooms").header("Authorization", bearer(BUYER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.chatRooms.length()").value(0));

		mockMvc.perform(post("/api/posts/" + post.getId() + "/chat-rooms").header("Authorization", bearer(BUYER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.created").value(false))
			.andExpect(jsonPath("$.data.chatRoomId").value(chatRoomId));
		entityManager.flush();

		Object leftAtAfterRejoin = jdbcTemplate.queryForObject(
			"select left_at from chat_participants where chat_room_id = ? and user_id = ?", Object.class, chatRoomId,
			BUYER_ID);
		assertThat(leftAtAfterRejoin).isNull();

		mockMvc.perform(get("/api/me/chat-rooms").header("Authorization", bearer(BUYER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.chatRooms.length()").value(1));
	}

	@Test
	void rejectsOwnPost() throws Exception {
		Post post = createPost(SELLER_ID);

		mockMvc.perform(post("/api/posts/" + post.getId() + "/chat-rooms").header("Authorization", bearer(SELLER_ID)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("CANNOT_CHAT_OWN_POST"));
	}

	@Test
	void rejectsBlockedRelationship() throws Exception {
		Post post = createPost(SELLER_ID);
		jdbcTemplate.update(
			"insert into user_blocks (blocker_id, blocked_id, created_at) values (?, ?, CURRENT_TIMESTAMP)",
			SELLER_ID, BUYER_ID);

		mockMvc.perform(post("/api/posts/" + post.getId() + "/chat-rooms").header("Authorization", bearer(BUYER_ID)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("USER_BLOCKED"));
	}

	@Test
	void returns404ForMissingPost() throws Exception {
		mockMvc.perform(post("/api/posts/999999/chat-rooms").header("Authorization", bearer(BUYER_ID)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"));
	}

	@Test
	void leaveReturns404ForNonParticipant() throws Exception {
		mockMvc.perform(delete("/api/me/chat-rooms/999999").header("Authorization", bearer(BUYER_ID)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("CHAT_ROOM_NOT_FOUND"));
	}

	@Test
	void listShowsPostPartnerAndUnreadCount() throws Exception {
		Post post = createPost(SELLER_ID);
		mockMvc.perform(post("/api/posts/" + post.getId() + "/chat-rooms").header("Authorization", bearer(BUYER_ID)))
			.andExpect(status().isOk());
		Long chatRoomId = jdbcTemplate.queryForObject(
			"select id from chat_rooms where post_id = ? and buyer_id = ?", Long.class, post.getId(), BUYER_ID);

		mockMvc.perform(post("/api/me/chat-rooms/" + chatRoomId + "/messages").header("Authorization", bearer(SELLER_ID))
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.content("{\"type\":\"TEXT\",\"content\":\"안녕하세요\"}"))
			.andExpect(status().isOk());
		entityManager.flush();

		mockMvc.perform(get("/api/me/chat-rooms").header("Authorization", bearer(BUYER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.chatRooms.length()").value(1))
			.andExpect(jsonPath("$.data.chatRooms[0].chatRoomId").value(chatRoomId))
			.andExpect(jsonPath("$.data.chatRooms[0].post.postId").value(post.getId()))
			.andExpect(jsonPath("$.data.chatRooms[0].post.title").value("우유 1L 팔아요"))
			.andExpect(jsonPath("$.data.chatRooms[0].partner.userId").value(SELLER_ID))
			.andExpect(jsonPath("$.data.chatRooms[0].partner.displayName").value("판매자"))
			.andExpect(jsonPath("$.data.chatRooms[0].lastMessage.preview").value("안녕하세요"))
			.andExpect(jsonPath("$.data.chatRooms[0].unreadCount").value(1));
	}

	@Test
	void listPaginatesWithCursor() throws Exception {
		for (int i = 0; i < 3; i++) {
			Post post = createPost(SELLER_ID);
			mockMvc.perform(post("/api/posts/" + post.getId() + "/chat-rooms").header("Authorization", bearer(BUYER_ID)))
				.andExpect(status().isOk());
		}
		entityManager.flush();

		var firstPage = mockMvc.perform(get("/api/me/chat-rooms").param("size", "2")
				.header("Authorization", bearer(BUYER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.chatRooms.length()").value(2))
			.andExpect(jsonPath("$.data.hasNext").value(true))
			.andReturn();

		String nextCursor = com.jayway.jsonpath.JsonPath.read(firstPage.getResponse().getContentAsString(),
			"$.data.nextCursor");

		mockMvc.perform(get("/api/me/chat-rooms").param("size", "2").param("cursor", nextCursor)
				.header("Authorization", bearer(BUYER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.chatRooms.length()").value(1))
			.andExpect(jsonPath("$.data.hasNext").value(false));
	}
}
