package com.vium.post.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
class PostDeleteControllerIntegrationTest {

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

	@Autowired
	private jakarta.persistence.EntityManager entityManager;

	private long nextCatalogId = 1;
	private long nextInventoryItemId = 1;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("insert into units (id,code,name) values (1,'ea','개'),(3,'kg','킬로그램')");
		jdbcTemplate.update("insert into storage_methods (id,code,name) values (1,'cold','냉장')");
		jdbcTemplate.update(
			"insert into item_statuses (id,code,name) values (1,'active','보유중'),(2,'consumed','소진'),(3,'disposed','폐기'),(4,'shared','나눔·판매')");
		jdbcTemplate.update("delete from ingredient_categories");
		jdbcTemplate.update("insert into ingredient_categories (id,name) values (1,'유제품')");
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

	private Post createPost(PostStatus status) {
		LocalDateTime now = LocalDateTime.now(clock);
		Post post = Post.builder()
			.authorId(USER_ID)
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
		return postRepository.save(post);
	}

	private String bearer(Long userId) {
		return "Bearer " + tokenService.issue(userId).accessToken();
	}

	@Test
	void deletesOpenPost() throws Exception {
		Post post = createPost(PostStatus.OPEN);

		mockMvc.perform(delete("/api/me/posts/" + post.getId()).header("Authorization", bearer(USER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data").doesNotExist());

		entityManager.flush();
		entityManager.clear();
		LocalDateTime deletedAt = jdbcTemplate.queryForObject(
			"select deleted_at from posts where id = ?", LocalDateTime.class, post.getId());
		assertThat(deletedAt).isNotNull();
	}

	@Test
	void deletesExpiredPost() throws Exception {
		Post post = createPost(PostStatus.EXPIRED);

		mockMvc.perform(delete("/api/me/posts/" + post.getId()).header("Authorization", bearer(USER_ID)))
			.andExpect(status().isOk());
	}

	@Test
	void returns404ForMissingPost() throws Exception {
		mockMvc.perform(delete("/api/me/posts/999999").header("Authorization", bearer(USER_ID)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"));
	}

	@Test
	void returns404ForOthersPost() throws Exception {
		Post post = createPost(PostStatus.OPEN);

		mockMvc.perform(delete("/api/me/posts/" + post.getId()).header("Authorization", bearer(OTHER_USER_ID)))
			.andExpect(status().isNotFound());
	}

	@Test
	void returns409ForReservedPostWithExactMessage() throws Exception {
		Post post = createPost(PostStatus.RESERVED);

		mockMvc.perform(delete("/api/me/posts/" + post.getId()).header("Authorization", bearer(USER_ID)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("POST_LOCKED"))
			.andExpect(jsonPath("$.error.message").value("거래가 진행 중이거나 완료된 게시물은 삭제할 수 없습니다."));
	}

	@Test
	void returns409ForCompletedPostWithExactMessage() throws Exception {
		Post post = createPost(PostStatus.COMPLETED);

		mockMvc.perform(delete("/api/me/posts/" + post.getId()).header("Authorization", bearer(USER_ID)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("POST_LOCKED"))
			.andExpect(jsonPath("$.error.message").value("거래가 진행 중이거나 완료된 게시물은 삭제할 수 없습니다."));
	}

	@Test
	void deletingPostUnlocksInventoryForFurtherEdits() throws Exception {
		Post post = createPost(PostStatus.OPEN);
		Long inventoryItemId = post.getInventoryItemId();

		mockMvc.perform(delete("/api/me/posts/" + post.getId()).header("Authorization", bearer(USER_ID)))
			.andExpect(status().isOk());

		mockMvc.perform(patch("/api/me/ingredients/" + inventoryItemId).header("Authorization", bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"customName":"수정됨","quantity":2,"unitId":3,"storageMethodId":1,
					 "purchasedOn":"2026-01-01","expiresOn":"2026-02-01"}
					"""))
			.andExpect(status().isOk());
	}
}
