package com.vium.post.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.auth.service.TokenService;
import com.vium.post.entity.Post;
import com.vium.post.entity.PostStatus;
import com.vium.post.entity.PostType;
import com.vium.post.repository.PostRepository;
import com.vium.trade.entity.Trade;
import com.vium.trade.entity.TradeReview;
import com.vium.trade.repository.TradeRepository;
import com.vium.trade.repository.TradeReviewRepository;
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

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:post-detail-get-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Transactional
@Sql(scripts = "/db/inventory-query-support.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class PostDetailControllerIntegrationTest {

	private static final Long AUTHOR_ID = 7L;
	private static final Long VIEWER_ID = 42L;
	private static final Long BLOCKED_VIEWER_ID = 44L;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TokenService tokenService;

	@Autowired
	private PostRepository postRepository;

	@Autowired
	private TradeRepository tradeRepository;

	@Autowired
	private TradeReviewRepository tradeReviewRepository;

	private long nextInventoryItemId = 1;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("insert into units (id,code,name) values (1,'ea','개')");
		jdbcTemplate.update("insert into storage_methods (id,code,name) values (1,'cold','냉장')");
		jdbcTemplate.update("insert into item_statuses (id,code,name) values (1,'active','보유중')");
		jdbcTemplate.update("delete from users");
		jdbcTemplate.update("""
			insert into users (id, email, display_name, created_at, updated_at)
			values (7, 'author@example.com', '작성자', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""");
	}

	private Long insertInventoryItem() {
		long id = nextInventoryItemId++;
		jdbcTemplate.update("""
			insert into inventory_items (id,user_id,custom_name,status_id,unit_id,storage_method_id,
			initial_quantity,remaining_quantity,expires_on,created_at,updated_at)
			values (?,?,'테스트 재료',1,1,1,1,1,'2026-12-31',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
			""", id, AUTHOR_ID);
		return id;
	}

	private Post createPost(PostStatus status, boolean deleted) {
		LocalDateTime now = LocalDateTime.now();
		Post post = Post.builder()
			.authorId(AUTHOR_ID)
			.inventoryItemId(insertInventoryItem())
			.type(PostType.SHARE)
			.status(status)
			.title("제목")
			.description("설명")
			.price(0)
			.isOpened(false)
			.latitude(37.5)
			.longitude(127.0)
			.createdAt(now)
			.updatedAt(now)
			.build();
		Post saved = postRepository.save(post);
		if (deleted) {
			org.springframework.test.util.ReflectionTestUtils.setField(saved, "deletedAt", now);
			postRepository.saveAndFlush(saved);
		}
		return saved;
	}

	private String bearer(Long userId) {
		return "Bearer " + tokenService.issue(userId).accessToken();
	}

	@Test
	void returnsDetailForOpenPost() throws Exception {
		Post post = createPost(PostStatus.OPEN, false);

		mockMvc.perform(get("/api/posts/" + post.getId()).header("Authorization", bearer(VIEWER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.postId").value(post.getId()))
			.andExpect(jsonPath("$.data.status").value("OPEN"))
			.andExpect(jsonPath("$.data.isMine").value(false))
			.andExpect(jsonPath("$.data.author.userId").value(AUTHOR_ID))
			.andExpect(jsonPath("$.data.author.mannerScore").doesNotExist())
			.andExpect(jsonPath("$.data.author.reviewCount").value(0));
	}

	@Test
	void expiredPostIsStillViewable() throws Exception {
		Post post = createPost(PostStatus.EXPIRED, false);

		mockMvc.perform(get("/api/posts/" + post.getId()).header("Authorization", bearer(VIEWER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("EXPIRED"));
	}

	@Test
	void completedPostIsStillViewable() throws Exception {
		Post post = createPost(PostStatus.COMPLETED, false);

		mockMvc.perform(get("/api/posts/" + post.getId()).header("Authorization", bearer(VIEWER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("COMPLETED"));
	}

	@Test
	void returns404ForDeletedPost() throws Exception {
		Post post = createPost(PostStatus.OPEN, true);

		mockMvc.perform(get("/api/posts/" + post.getId()).header("Authorization", bearer(VIEWER_ID)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"));
	}

	@Test
	void returns404ForMissingPost() throws Exception {
		mockMvc.perform(get("/api/posts/999999").header("Authorization", bearer(VIEWER_ID)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"));
	}

	@Test
	void returns404WhenBlockedRelationshipExists() throws Exception {
		Post post = createPost(PostStatus.OPEN, false);
		jdbcTemplate.update(
			"insert into user_blocks (blocker_id, blocked_id, created_at) values (?, ?, CURRENT_TIMESTAMP)",
			BLOCKED_VIEWER_ID, AUTHOR_ID);

		mockMvc.perform(get("/api/posts/" + post.getId()).header("Authorization", bearer(BLOCKED_VIEWER_ID)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"));
	}

	@Test
	void computesDistanceWhenCoordinatesGiven() throws Exception {
		Post post = createPost(PostStatus.OPEN, false); // lat 37.5, lng 127.0

		mockMvc.perform(get("/api/posts/" + post.getId())
				.header("Authorization", bearer(VIEWER_ID))
				.param("latitude", "37.5")
				.param("longitude", "127.0"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.distanceMeters").value(0));
	}

	@Test
	void distanceMetersIsNullWithoutCoordinates() throws Exception {
		Post post = createPost(PostStatus.OPEN, false);

		mockMvc.perform(get("/api/posts/" + post.getId()).header("Authorization", bearer(VIEWER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.distanceMeters").doesNotExist());
	}

	@Test
	void showsActiveTradeIdWhenViewerIsParticipant() throws Exception {
		Post post = createPost(PostStatus.RESERVED, false);
		Trade trade = tradeRepository.save(
			Trade.request(post.getId(), VIEWER_ID, AUTHOR_ID, 1000, LocalDateTime.now()));

		mockMvc.perform(get("/api/posts/" + post.getId()).header("Authorization", bearer(VIEWER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.activeTradeId").value(trade.getId()));
	}

	@Test
	void mannerScoreIsAveragedToOneDecimalPlace() throws Exception {
		Post post = createPost(PostStatus.OPEN, false);
		LocalDateTime now = LocalDateTime.now();
		Trade completed = Trade.request(post.getId(), 100L, AUTHOR_ID, 1000, now);
		completed.accept(now);
		completed.complete(null, now);
		Trade trade = tradeRepository.save(completed);
		tradeReviewRepository.save(TradeReview.create(trade.getId(), 100L, AUTHOR_ID, 5, null, now));
		tradeReviewRepository.save(TradeReview.create(trade.getId(), 101L, AUTHOR_ID, 4, null, now));

		mockMvc.perform(get("/api/posts/" + post.getId()).header("Authorization", bearer(VIEWER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.author.mannerScore").value(4.5))
			.andExpect(jsonPath("$.data.author.reviewCount").value(2));
	}
}
