package com.vium.post.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.auth.service.TokenService;
import com.vium.post.entity.Post;
import com.vium.post.entity.PostStatus;
import com.vium.post.entity.PostType;
import com.vium.post.repository.PostRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * post 도메인의 실제 InventoryLockChecker 구현체(PostInventoryLockChecker)가
 * InventoryService.assertNotLocked에 자동으로 연결되어 재고 수정·소진/폐기 PATCH를 막는지 검증한다.
 * (InventoryLockCheckerIntegrationTest는 이 구현체를 Mockito로 대체하므로 실제 동작을 검증하지 못한다.)
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:post-lock-checker-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Transactional
class PostInventoryLockCheckerIntegrationTest {

	private static final Long USER_ID = 42L;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TokenService tokenService;

	@Autowired
	private PostInventoryLockChecker postInventoryLockChecker;

	@Autowired
	private PostRepository postRepository;

	private long nextInventoryItemId = 1000;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("insert into units (id,code,name) values (1,'ea','개')");
		jdbcTemplate.update("insert into storage_methods (id,code,name) values (1,'cold','냉장')");
		jdbcTemplate.update(
			"insert into item_statuses (id,code,name) values (1,'active','보유중'),(2,'consumed','소진'),(3,'disposed','폐기'),(4,'shared','공유중')");
		jdbcTemplate.update("insert into event_sources (id,code,name) values (1,'manual','수동')");
	}

	private Long insertInventoryItem() {
		long id = nextInventoryItemId++;
		jdbcTemplate.update("""
			insert into inventory_items (id,user_id,custom_name,status_id,unit_id,storage_method_id,
			initial_quantity,remaining_quantity,created_at,updated_at)
			values (?,?,'테스트 재료',1,1,1,10,10,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
			""", id, USER_ID);
		return id;
	}

	private void createPost(Long inventoryItemId, PostStatus status, boolean deleted) {
		LocalDateTime now = LocalDateTime.now();
		Post post = Post.builder()
			.authorId(USER_ID)
			.inventoryItemId(inventoryItemId)
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
	}

	private String bearer() {
		return "Bearer " + tokenService.issue(USER_ID).accessToken();
	}

	@Test
	void isLockedTrueForOpenPost() {
		Long inventoryItemId = insertInventoryItem();
		createPost(inventoryItemId, PostStatus.OPEN, false);

		assertThat(postInventoryLockChecker.isLocked(inventoryItemId)).isTrue();
	}

	@Test
	void isLockedTrueForReservedPost() {
		Long inventoryItemId = insertInventoryItem();
		createPost(inventoryItemId, PostStatus.RESERVED, false);

		assertThat(postInventoryLockChecker.isLocked(inventoryItemId)).isTrue();
	}

	@Test
	void isLockedFalseForCompletedPost() {
		Long inventoryItemId = insertInventoryItem();
		createPost(inventoryItemId, PostStatus.COMPLETED, false);

		assertThat(postInventoryLockChecker.isLocked(inventoryItemId)).isFalse();
	}

	@Test
	void isLockedFalseForExpiredPost() {
		Long inventoryItemId = insertInventoryItem();
		createPost(inventoryItemId, PostStatus.EXPIRED, false);

		assertThat(postInventoryLockChecker.isLocked(inventoryItemId)).isFalse();
	}

	@Test
	void isLockedFalseForDeletedOpenPost() {
		Long inventoryItemId = insertInventoryItem();
		createPost(inventoryItemId, PostStatus.OPEN, true);

		assertThat(postInventoryLockChecker.isLocked(inventoryItemId)).isFalse();
	}

	@Test
	void isLockedFalseWithoutAnyPost() {
		Long inventoryItemId = insertInventoryItem();

		assertThat(postInventoryLockChecker.isLocked(inventoryItemId)).isFalse();
	}

	@Test
	void ingredientUpdateIsRejectedWhenInventoryHasOpenPost() throws Exception {
		Long inventoryItemId = insertInventoryItem();
		createPost(inventoryItemId, PostStatus.OPEN, false);

		mockMvc.perform(patch("/api/me/ingredients/" + inventoryItemId)
				.header("Authorization", bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"customName":"수정됨","quantity":10,"unitId":1,"storageMethodId":1,
					 "purchasedOn":"2026-01-01","expiresOn":"2026-02-01"}
					"""))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("INVENTORY_LOCKED"));
	}

	@Test
	void ingredientUpdateSucceedsWhenInventoryHasCompletedPost() throws Exception {
		Long inventoryItemId = insertInventoryItem();
		createPost(inventoryItemId, PostStatus.COMPLETED, false);

		mockMvc.perform(patch("/api/me/ingredients/" + inventoryItemId)
				.header("Authorization", bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"customName":"수정됨","quantity":10,"unitId":1,"storageMethodId":1,
					 "purchasedOn":"2026-01-01","expiresOn":"2026-02-01"}
					"""))
			.andExpect(status().isOk());
	}

	@Test
	void disposePatchIsRejectedWhenInventoryHasReservedPost() throws Exception {
		Long inventoryItemId = insertInventoryItem();
		createPost(inventoryItemId, PostStatus.RESERVED, false);

		mockMvc.perform(patch("/api/me/ingredients/" + inventoryItemId + "/status")
				.header("Authorization", bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"status":"consumed","quantity":2}
					"""))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("INVENTORY_LOCKED"));
	}

	@Test
	void disposePatchSucceedsWhenInventoryHasNoPost() throws Exception {
		Long inventoryItemId = insertInventoryItem();

		mockMvc.perform(patch("/api/me/ingredients/" + inventoryItemId + "/status")
				.header("Authorization", bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"status":"consumed","quantity":2}
					"""))
			.andExpect(status().isOk());
	}
}
