package com.vium.post.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vium.post.entity.Post;
import com.vium.post.entity.PostStatus;
import com.vium.post.entity.PostType;
import com.vium.post.repository.PostRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:post-expiry-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@Transactional
class PostExpiryServiceIntegrationTest {

	private static final Long USER_ID = 42L;

	@Autowired
	private PostExpiryService postExpiryService;

	@Autowired
	private PostRepository postRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private Clock clock;

	private long nextInventoryItemId = 1;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("insert into units (id,code,name) values (1,'ea','개')");
		jdbcTemplate.update("insert into storage_methods (id,code,name) values (1,'cold','냉장')");
		jdbcTemplate.update("insert into item_statuses (id,code,name) values (1,'active','보유중')");
	}

	private Long insertInventoryItem(LocalDate expiresOn) {
		long id = nextInventoryItemId++;
		jdbcTemplate.update("""
			insert into inventory_items (id,user_id,custom_name,status_id,unit_id,storage_method_id,
			initial_quantity,remaining_quantity,expires_on,created_at,updated_at)
			values (?,?,'테스트 재료',1,1,1,1,1,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
			""", id, USER_ID, expiresOn);
		return id;
	}

	private Post createPost(PostStatus status, Long inventoryItemId) {
		LocalDateTime now = LocalDateTime.now(clock);
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
		return postRepository.save(post);
	}

	@Test
	void expiresOpenPostWhosePastExpiryIngredient() {
		Long itemId = insertInventoryItem(LocalDate.now(clock).minusDays(1));
		Post post = createPost(PostStatus.OPEN, itemId);

		int processed = postExpiryService.expireOpenPosts();

		assertThat(processed).isEqualTo(1);
		Post reloaded = postRepository.findById(post.getId()).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(PostStatus.EXPIRED);
	}

	@Test
	void doesNotExpireOpenPostWithFutureExpiry() {
		Long itemId = insertInventoryItem(LocalDate.now(clock).plusDays(5));
		Post post = createPost(PostStatus.OPEN, itemId);

		int processed = postExpiryService.expireOpenPosts();

		assertThat(processed).isZero();
		Post reloaded = postRepository.findById(post.getId()).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(PostStatus.OPEN);
	}

	@Test
	void doesNotExpireOpenPostWhenIngredientExpiresToday() {
		Long itemId = insertInventoryItem(LocalDate.now(clock));
		Post post = createPost(PostStatus.OPEN, itemId);

		int processed = postExpiryService.expireOpenPosts();

		assertThat(processed).isZero();
		Post reloaded = postRepository.findById(post.getId()).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(PostStatus.OPEN);
	}

	@Test
	void leavesReservedPostUntouchedEvenIfIngredientExpired() {
		Long itemId = insertInventoryItem(LocalDate.now(clock).minusDays(1));
		Post post = createPost(PostStatus.RESERVED, itemId);

		int processed = postExpiryService.expireOpenPosts();

		assertThat(processed).isZero();
		Post reloaded = postRepository.findById(post.getId()).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(PostStatus.RESERVED);
	}

	@Test
	void ignoresAlreadyDeletedOpenPost() {
		Long itemId = insertInventoryItem(LocalDate.now(clock).minusDays(1));
		Post post = createPost(PostStatus.OPEN, itemId);
		post.softDelete(LocalDateTime.now(clock));
		postRepository.saveAndFlush(post);

		int processed = postExpiryService.expireOpenPosts();

		assertThat(processed).isZero();
	}
}
