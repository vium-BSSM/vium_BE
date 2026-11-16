package com.vium.post.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.auth.service.TokenService;
import com.vium.post.entity.Post;
import com.vium.post.entity.PostStatus;
import com.vium.post.entity.PostType;
import com.vium.post.repository.PostRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:post-domain-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Transactional
class PostLikeControllerIntegrationTest {

	private static final Long USER_ID = 42L;
	private static final Long BLOCKED_AUTHOR_ID = 44L;

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

	private long nextInventoryItemId = 1;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("delete from user_blocks");
	}

	private Post createPost(Long authorId, Long inventoryItemId) {
		LocalDateTime now = LocalDateTime.now(clock);
		Post post = Post.builder()
			.authorId(authorId)
			.inventoryItemId(inventoryItemId)
			.type(PostType.SHARE)
			.status(PostStatus.OPEN)
			.title("제목")
			.description("설명")
			.price(0)
			.isOpened(false)
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
	void likingTwiceIsIdempotent() throws Exception {
		Post post = createPost(USER_ID + 1, nextInventoryItemId++);

		mockMvc.perform(put("/api/posts/" + post.getId() + "/like").header("Authorization", bearer(USER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.liked").value(true))
			.andExpect(jsonPath("$.data.likeCount").value(1));

		mockMvc.perform(put("/api/posts/" + post.getId() + "/like").header("Authorization", bearer(USER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.liked").value(true))
			.andExpect(jsonPath("$.data.likeCount").value(1));
	}

	@Test
	void unlikingTwiceIsIdempotent() throws Exception {
		Post post = createPost(USER_ID + 1, nextInventoryItemId++);
		mockMvc.perform(put("/api/posts/" + post.getId() + "/like").header("Authorization", bearer(USER_ID)))
			.andExpect(status().isOk());

		mockMvc.perform(delete("/api/posts/" + post.getId() + "/like").header("Authorization", bearer(USER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.liked").value(false))
			.andExpect(jsonPath("$.data.likeCount").value(0));

		mockMvc.perform(delete("/api/posts/" + post.getId() + "/like").header("Authorization", bearer(USER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.liked").value(false))
			.andExpect(jsonPath("$.data.likeCount").value(0));
	}

	@Test
	void unlikeWithoutPriorLikeIsNoop() throws Exception {
		Post post = createPost(USER_ID + 1, nextInventoryItemId++);

		mockMvc.perform(delete("/api/posts/" + post.getId() + "/like").header("Authorization", bearer(USER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.liked").value(false))
			.andExpect(jsonPath("$.data.likeCount").value(0));
	}

	@Test
	void likeCountReflectsMultipleUsers() throws Exception {
		Post post = createPost(USER_ID + 1, nextInventoryItemId++);

		mockMvc.perform(put("/api/posts/" + post.getId() + "/like").header("Authorization", bearer(USER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.likeCount").value(1));

		mockMvc.perform(put("/api/posts/" + post.getId() + "/like").header("Authorization", bearer(USER_ID + 2)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.likeCount").value(2));
	}

	@Test
	void returns404ForMissingPost() throws Exception {
		mockMvc.perform(put("/api/posts/999999/like").header("Authorization", bearer(USER_ID)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"));
	}

	@Test
	void returns404ForDeletedPost() throws Exception {
		Post post = createPost(USER_ID + 1, nextInventoryItemId++);
		jdbcTemplate.update("update posts set deleted_at = CURRENT_TIMESTAMP where id = ?", post.getId());

		mockMvc.perform(put("/api/posts/" + post.getId() + "/like").header("Authorization", bearer(USER_ID)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"));
	}

	@Test
	void returns404WhenBlockedRelationshipExists() throws Exception {
		Post post = createPost(BLOCKED_AUTHOR_ID, nextInventoryItemId++);
		jdbcTemplate.update(
			"insert into user_blocks (blocker_id, blocked_id, created_at) values (?, ?, CURRENT_TIMESTAMP)",
			USER_ID, BLOCKED_AUTHOR_ID);

		mockMvc.perform(put("/api/posts/" + post.getId() + "/like").header("Authorization", bearer(USER_ID)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"));
	}
}
