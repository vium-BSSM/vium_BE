package com.vium.post.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.auth.service.TokenService;
import com.vium.post.entity.Post;
import com.vium.post.entity.PostStatus;
import com.vium.post.entity.PostType;
import com.vium.post.repository.PostRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:post-list-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Transactional
@Sql(scripts = "/db/inventory-query-support.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class PostListControllerIntegrationTest {

	private static final Long VIEWER_ID = 42L;
	private static final double BASE_LAT = 37.5;
	private static final double BASE_LNG = 127.0;

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
	private ObjectMapper objectMapper;

	private long nextInventoryItemId = 1;
	private long authorCounter = 100;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("insert into units (id,code,name) values (1,'ea','개')");
		jdbcTemplate.update("insert into storage_methods (id,code,name) values (1,'cold','냉장')");
		jdbcTemplate.update("insert into item_statuses (id,code,name) values (1,'active','보유중')");
	}

	private Long insertInventoryItem(Long authorId, LocalDate expiresOn) {
		long id = nextInventoryItemId++;
		jdbcTemplate.update("""
			insert into inventory_items (id,user_id,custom_name,status_id,unit_id,storage_method_id,
			initial_quantity,remaining_quantity,expires_on,created_at,updated_at)
			values (?,?,'테스트 재료',1,1,1,1,1,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
			""", id, authorId, expiresOn);
		return id;
	}

	/** latOffsetMeters 만큼 북쪽으로 이동한 위치에, authorId가 올린 OPEN 게시물을 만든다. */
	private Post createPostAt(Long authorId, double latOffsetMeters, PostStatus status, LocalDateTime createdAt,
			LocalDate expiresOn) {
		double lat = BASE_LAT + latOffsetMeters / 110_000.0;
		Post post = Post.builder()
			.authorId(authorId)
			.inventoryItemId(insertInventoryItem(authorId, expiresOn))
			.type(PostType.SHARE)
			.status(status)
			.title("제목")
			.description("설명")
			.price(0)
			.isOpened(false)
			.latitude(lat)
			.longitude(BASE_LNG)
			.createdAt(createdAt)
			.updatedAt(createdAt)
			.build();
		return postRepository.save(post);
	}

	private Post createPostAt(double latOffsetMeters, PostStatus status, LocalDateTime createdAt) {
		return createPostAt(authorCounter++, latOffsetMeters, status, createdAt, LocalDate.now(clock).plusDays(10));
	}

	private String bearer(Long userId) {
		return "Bearer " + tokenService.issue(userId).accessToken();
	}

	private LocalDateTime now() {
		return LocalDateTime.now(clock);
	}

	@Test
	void includesPostsWithinRadiusAndExcludesOutside() throws Exception {
		Post inside = createPostAt(500, PostStatus.OPEN, now());
		Post outside = createPostAt(5000, PostStatus.OPEN, now());

		MvcResult result = mockMvc.perform(get("/api/posts")
				.header("Authorization", bearer(VIEWER_ID))
				.param("latitude", String.valueOf(BASE_LAT))
				.param("longitude", String.valueOf(BASE_LNG))
				.param("distance", "1000"))
			.andExpect(status().isOk())
			.andReturn();

		List<Long> ids = extractPostIds(result);
		assertThat(ids).contains(inside.getId()).doesNotContain(outside.getId());
	}

	@Test
	void sortsByDistanceAscendingByDefault() throws Exception {
		Post far = createPostAt(900, PostStatus.OPEN, now());
		Post near = createPostAt(100, PostStatus.OPEN, now());

		MvcResult result = mockMvc.perform(get("/api/posts")
				.header("Authorization", bearer(VIEWER_ID))
				.param("latitude", String.valueOf(BASE_LAT))
				.param("longitude", String.valueOf(BASE_LNG)))
			.andExpect(status().isOk())
			.andReturn();

		List<Long> ids = extractPostIds(result);
		assertThat(ids.indexOf(near.getId())).isLessThan(ids.indexOf(far.getId()));
	}

	@Test
	void sortsByLatestWhenRequested() throws Exception {
		Post older = createPostAt(100, PostStatus.OPEN, now().minusMinutes(10));
		Post newer = createPostAt(900, PostStatus.OPEN, now());

		MvcResult result = mockMvc.perform(get("/api/posts")
				.header("Authorization", bearer(VIEWER_ID))
				.param("latitude", String.valueOf(BASE_LAT))
				.param("longitude", String.valueOf(BASE_LNG))
				.param("distance", "10000")
				.param("sort", "LATEST"))
			.andExpect(status().isOk())
			.andReturn();

		List<Long> ids = extractPostIds(result);
		assertThat(ids.indexOf(newer.getId())).isLessThan(ids.indexOf(older.getId()));
	}

	@Test
	void paginatesAcrossTwoPagesWithDistanceCursor() throws Exception {
		for (int i = 0; i < 3; i++) {
			createPostAt(100.0 * (i + 1), PostStatus.OPEN, now());
		}

		MvcResult firstPage = mockMvc.perform(get("/api/posts")
				.header("Authorization", bearer(VIEWER_ID))
				.param("latitude", String.valueOf(BASE_LAT))
				.param("longitude", String.valueOf(BASE_LNG))
				.param("size", "2"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.hasNext").value(true))
			.andReturn();

		JsonNode firstBody = objectMapper.readTree(firstPage.getResponse().getContentAsByteArray());
		String nextCursor = firstBody.get("data").get("nextCursor").asString();
		List<Long> firstIds = extractPostIds(firstPage);
		assertThat(firstIds).hasSize(2);

		MvcResult secondPage = mockMvc.perform(get("/api/posts")
				.header("Authorization", bearer(VIEWER_ID))
				.param("latitude", String.valueOf(BASE_LAT))
				.param("longitude", String.valueOf(BASE_LNG))
				.param("size", "2")
				.param("cursor", nextCursor))
			.andExpect(status().isOk())
			.andReturn();

		List<Long> secondIds = extractPostIds(secondPage);
		assertThat(secondIds).hasSize(1);
		assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);
	}

	@Test
	void excludesPostsFromBlockedAuthors() throws Exception {
		Post blockedAuthorPost = createPostAt(100, PostStatus.OPEN, now());
		Post visible = createPostAt(100, PostStatus.OPEN, now());
		jdbcTemplate.update(
			"insert into user_blocks (blocker_id, blocked_id, created_at) values (?, ?, CURRENT_TIMESTAMP)",
			VIEWER_ID, blockedAuthorPost.getAuthorId());

		MvcResult result = mockMvc.perform(get("/api/posts")
				.header("Authorization", bearer(VIEWER_ID))
				.param("latitude", String.valueOf(BASE_LAT))
				.param("longitude", String.valueOf(BASE_LNG)))
			.andExpect(status().isOk())
			.andReturn();

		List<Long> ids = extractPostIds(result);
		assertThat(ids).contains(visible.getId()).doesNotContain(blockedAuthorPost.getId());
	}

	@Test
	void excludesDeletedAndCompletedPosts() throws Exception {
		Post deleted = createPostAt(100, PostStatus.OPEN, now());
		org.springframework.test.util.ReflectionTestUtils.setField(deleted, "deletedAt", now());
		postRepository.saveAndFlush(deleted);
		Post completed = createPostAt(100, PostStatus.COMPLETED, now());
		Post open = createPostAt(100, PostStatus.OPEN, now());
		Post reserved = createPostAt(100, PostStatus.RESERVED, now());

		MvcResult result = mockMvc.perform(get("/api/posts")
				.header("Authorization", bearer(VIEWER_ID))
				.param("latitude", String.valueOf(BASE_LAT))
				.param("longitude", String.valueOf(BASE_LNG)))
			.andExpect(status().isOk())
			.andReturn();

		List<Long> ids = extractPostIds(result);
		assertThat(ids).contains(open.getId(), reserved.getId())
			.doesNotContain(deleted.getId(), completed.getId());
	}

	@Test
	void excludesPostsWithExpiredIngredient() throws Exception {
		Long authorId = authorCounter++;
		Post expired = createPostAt(authorId, 100, PostStatus.OPEN, now(), LocalDate.now(clock).minusDays(1));
		Post notExpired = createPostAt(100, PostStatus.OPEN, now());

		MvcResult result = mockMvc.perform(get("/api/posts")
				.header("Authorization", bearer(VIEWER_ID))
				.param("latitude", String.valueOf(BASE_LAT))
				.param("longitude", String.valueOf(BASE_LNG)))
			.andExpect(status().isOk())
			.andReturn();

		List<Long> ids = extractPostIds(result);
		assertThat(ids).contains(notExpired.getId()).doesNotContain(expired.getId());
	}

	@Test
	void returns400ForInvalidCursor() throws Exception {
		mockMvc.perform(get("/api/posts")
				.header("Authorization", bearer(VIEWER_ID))
				.param("latitude", String.valueOf(BASE_LAT))
				.param("longitude", String.valueOf(BASE_LNG))
				.param("cursor", "not-a-valid-cursor!!"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	void returns400ForMissingCoordinates() throws Exception {
		mockMvc.perform(get("/api/posts").header("Authorization", bearer(VIEWER_ID)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	void returns400ForInvalidDistance() throws Exception {
		mockMvc.perform(get("/api/posts")
				.header("Authorization", bearer(VIEWER_ID))
				.param("latitude", String.valueOf(BASE_LAT))
				.param("longitude", String.valueOf(BASE_LNG))
				.param("distance", "2000"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	private List<Long> extractPostIds(MvcResult result) throws Exception {
		JsonNode body = objectMapper.readTree(result.getResponse().getContentAsByteArray());
		List<Long> ids = new java.util.ArrayList<>();
		body.get("data").get("posts").forEach(node -> ids.add(node.get("postId").asLong()));
		return ids;
	}
}
