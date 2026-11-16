package com.vium.post.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.auth.service.TokenService;
import com.vium.post.entity.Post;
import com.vium.post.entity.PostStatus;
import com.vium.post.entity.PostType;
import com.vium.post.repository.PostRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:post-domain-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Transactional
@Sql(scripts = "/db/inventory-query-support.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class PostUpdateControllerIntegrationTest {

	private static final Long USER_ID = 42L;
	private static final Long OTHER_USER_ID = 43L;
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

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

	private long nextCatalogId = 1;
	private long nextInventoryItemId = 1;
	private long nextImageId = 1;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("insert into units (id,code,name) values (1,'ea','개'),(3,'kg','킬로그램')");
		jdbcTemplate.update("insert into storage_methods (id,code,name) values (1,'cold','냉장')");
		jdbcTemplate.update(
			"insert into item_statuses (id,code,name) values (1,'active','보유중'),(2,'consumed','소진'),(3,'disposed','폐기'),(4,'shared','나눔·판매')");
		jdbcTemplate.update("delete from ingredient_categories");
		jdbcTemplate.update("insert into ingredient_categories (id,name) values (1,'유제품')");
		jdbcTemplate.update("delete from users");
		jdbcTemplate.update("""
			insert into users (id, email, display_name, created_at, updated_at)
			values (42, 'owner@example.com', '주인', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
			       (43, 'other@example.com', '남', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""");
	}

	private Long insertInventoryItem() {
		long id = nextInventoryItemId++;
		long catalogId = nextCatalogId++;
		jdbcTemplate.update("""
			insert into ingredient_catalog (id, category_id, default_unit_id, name, post_allowed, created_at)
			values (?, 1, 3, '테스트재료', true, CURRENT_TIMESTAMP)
			""", catalogId);
		jdbcTemplate.update("""
			insert into inventory_items (id,user_id,ingredient_catalog_id,status_id,unit_id,
			storage_method_id,initial_quantity,remaining_quantity,expires_on,created_at,updated_at)
			values (?,?,?,(select id from item_statuses where code = 'active'),3,1,2,2,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
			""", id, USER_ID, catalogId, LocalDate.now(SEOUL).plusDays(10));
		return id;
	}

	private Post createPost(PostType type, PostStatus status, Integer price, Integer recommendedPrice) {
		LocalDateTime now = LocalDateTime.now(clock);
		Post post = Post.builder()
			.authorId(USER_ID)
			.inventoryItemId(insertInventoryItem())
			.type(type)
			.status(status)
			.title("원래제목")
			.description("원래설명")
			.price(price == null ? 0 : price)
			.recommendedPrice(recommendedPrice)
			.isOpened(false)
			.latitude(37.5)
			.longitude(127.0)
			.createdAt(now)
			.updatedAt(now)
			.build();
		return postRepository.save(post);
	}

	private Long insertPostImage(Long userId) {
		long id = nextImageId++;
		jdbcTemplate.update("""
			insert into uploaded_images (id, user_id, purpose, bucket, object_key, content_type, size_bytes, ready, created_at)
			values (?, ?, 'POST', 'public', ?, 'image/jpeg', 100, true, CURRENT_TIMESTAMP)
			""", id, userId, "images/" + userId + "/" + id + ".jpg");
		return id;
	}

	private String bearer(Long userId) {
		return "Bearer " + tokenService.issue(userId).accessToken();
	}

	@Test
	void updatesTitleOnly() throws Exception {
		Post post = createPost(PostType.SHARE, PostStatus.OPEN, 0, null);

		mockMvc.perform(patch("/api/me/posts/" + post.getId()).header("Authorization", bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"title":"새제목"}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.title").value("새제목"))
			.andExpect(jsonPath("$.data.description").value("원래설명"));
	}

	@Test
	void rejectsJsonNullTitle() throws Exception {
		Post post = createPost(PostType.SHARE, PostStatus.OPEN, 0, null);

		mockMvc.perform(patch("/api/me/posts/" + post.getId()).header("Authorization", bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"title":null}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	void rejectsEmptyBody() throws Exception {
		Post post = createPost(PostType.SHARE, PostStatus.OPEN, 0, null);

		mockMvc.perform(patch("/api/me/posts/" + post.getId()).header("Authorization", bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	void rejectsJsonNullDescription() throws Exception {
		Post post = createPost(PostType.SHARE, PostStatus.OPEN, 0, null);

		mockMvc.perform(patch("/api/me/posts/" + post.getId()).header("Authorization", bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"description":null}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	void rejectsJsonNullPrice() throws Exception {
		Post post = createPost(PostType.SALE, PostStatus.OPEN, 1000, 1000);

		mockMvc.perform(patch("/api/me/posts/" + post.getId()).header("Authorization", bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"price":null}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	void rejectsJsonNullImageIds() throws Exception {
		Post post = createPost(PostType.SHARE, PostStatus.OPEN, 0, null);

		mockMvc.perform(patch("/api/me/posts/" + post.getId()).header("Authorization", bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"imageIds":null}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	void updatesPriceWithinRangeForSale() throws Exception {
		Post post = createPost(PostType.SALE, PostStatus.OPEN, 1000, 1000);
		// range [800, 1200]

		mockMvc.perform(patch("/api/me/posts/" + post.getId()).header("Authorization", bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"price":1100}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.price").value(1100));
	}

	@Test
	void rejectsPriceOutOfRangeForSale() throws Exception {
		Post post = createPost(PostType.SALE, PostStatus.OPEN, 1000, 1000);

		mockMvc.perform(patch("/api/me/posts/" + post.getId()).header("Authorization", bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"price":1201}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("PRICE_OUT_OF_RANGE"));
	}

	@Test
	void rejectsPriceChangeForShare() throws Exception {
		Post post = createPost(PostType.SHARE, PostStatus.OPEN, 0, null);

		mockMvc.perform(patch("/api/me/posts/" + post.getId()).header("Authorization", bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"price":500}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	void replacesImagesEntirely() throws Exception {
		Post post = createPost(PostType.SHARE, PostStatus.OPEN, 0, null);
		Long image1 = insertPostImage(USER_ID);
		Long image2 = insertPostImage(USER_ID);

		mockMvc.perform(patch("/api/me/posts/" + post.getId()).header("Authorization", bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"imageIds":[%d,%d]}
					""".formatted(image1, image2)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.images.length()").value(2));

		mockMvc.perform(patch("/api/me/posts/" + post.getId()).header("Authorization", bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"imageIds":[]}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.images.length()").value(0));
	}

	@Test
	void rejectsImageNotOwnedByCaller() throws Exception {
		Post post = createPost(PostType.SHARE, PostStatus.OPEN, 0, null);
		Long otherUsersImageId = insertPostImage(OTHER_USER_ID);

		mockMvc.perform(patch("/api/me/posts/" + post.getId()).header("Authorization", bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"imageIds":[%d]}
					""".formatted(otherUsersImageId)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.error.message").value("내가 올린 게시물 이미지만 사용할 수 있습니다."));
	}

	@Test
	void returns404ForMissingPost() throws Exception {
		mockMvc.perform(patch("/api/me/posts/999999").header("Authorization", bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"title":"새제목"}
					"""))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"));
	}

	@Test
	void returns404ForOthersPost() throws Exception {
		Post post = createPost(PostType.SHARE, PostStatus.OPEN, 0, null);

		mockMvc.perform(patch("/api/me/posts/" + post.getId()).header("Authorization", bearer(OTHER_USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"title":"새제목"}
					"""))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"));
	}

	@Test
	void returns409ForReservedPostWithDefaultMessage() throws Exception {
		Post post = createPost(PostType.SHARE, PostStatus.RESERVED, 0, null);

		mockMvc.perform(patch("/api/me/posts/" + post.getId()).header("Authorization", bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"title":"새제목"}
					"""))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("POST_LOCKED"))
			.andExpect(jsonPath("$.error.message").value("거래가 진행 중인 게시물은 수정할 수 없습니다."));
	}

	@Test
	void returns409ForCompletedPost() throws Exception {
		Post post = createPost(PostType.SHARE, PostStatus.COMPLETED, 0, null);

		mockMvc.perform(patch("/api/me/posts/" + post.getId()).header("Authorization", bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"title":"새제목"}
					"""))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("POST_LOCKED"));
	}

	@Test
	void returns409ForExpiredPost() throws Exception {
		Post post = createPost(PostType.SHARE, PostStatus.EXPIRED, 0, null);

		mockMvc.perform(patch("/api/me/posts/" + post.getId()).header("Authorization", bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"title":"새제목"}
					"""))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("POST_LOCKED"));
	}

	@Test
	void rejectsTitleLongerThanFiftyCharacters() throws Exception {
		Post post = createPost(PostType.SHARE, PostStatus.OPEN, 0, null);
		String longTitle = "가".repeat(51);

		mockMvc.perform(patch("/api/me/posts/" + post.getId()).header("Authorization", bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"title":"%s"}
					""".formatted(longTitle)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	void updateResponseIncludesFullPostDetailShape() throws Exception {
		Post post = createPost(PostType.SHARE, PostStatus.OPEN, 0, null);

		mockMvc.perform(patch("/api/me/posts/" + post.getId()).header("Authorization", bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"title":"새제목"}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.postId").value(post.getId()))
			.andExpect(jsonPath("$.data.type").value("SHARE"))
			.andExpect(jsonPath("$.data.status").value("OPEN"))
			.andExpect(jsonPath("$.data.isMine").value(true))
			.andExpect(jsonPath("$.data.likedByMe").value(false))
			.andExpect(jsonPath("$.data.author.userId").value(USER_ID))
			.andExpect(jsonPath("$.data.author.displayName").value("주인"))
			.andExpect(jsonPath("$.data.author.mannerScore").doesNotExist())
			.andExpect(jsonPath("$.data.author.reviewCount").value(0))
			.andExpect(jsonPath("$.data.ingredient.isOpened").value(false))
			.andExpect(jsonPath("$.data.activeTradeId").doesNotExist())
			.andExpect(jsonPath("$.data.distanceMeters").doesNotExist());
	}

	@Test
	void reopenedPostCanBeEditedAgain() throws Exception {
		// RESERVED -> OPEN 전이 후에는 다시 수정 가능해야 한다(1단계 PostStateService.markOpen 재사용 확인).
		Post post = createPost(PostType.SHARE, PostStatus.RESERVED, 0, null);
		post.markOpen(LocalDateTime.now(clock));
		postRepository.saveAndFlush(post);

		mockMvc.perform(patch("/api/me/posts/" + post.getId()).header("Authorization", bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"title":"다시수정"}
					"""))
			.andExpect(status().isOk());
	}
}
