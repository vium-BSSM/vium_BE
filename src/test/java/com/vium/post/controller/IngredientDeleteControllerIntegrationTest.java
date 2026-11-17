package com.vium.post.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.auth.service.TokenService;
import com.vium.post.entity.Post;
import com.vium.post.entity.PostStatus;
import com.vium.post.entity.PostType;
import com.vium.post.repository.PostRepository;
import com.vium.trade.entity.Trade;
import com.vium.trade.repository.TradeRepository;
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

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:ingredient-delete-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Transactional
@Sql(scripts = "/db/inventory-query-support.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class IngredientDeleteControllerIntegrationTest {

	private static final Long USER_ID = 42L;
	private static final Long OTHER_USER_ID = 43L;

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
	private jakarta.persistence.EntityManager entityManager;

	private long nextInventoryItemId = 1;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("insert into units (id,code,name) values (1,'ea','개')");
		jdbcTemplate.update("insert into storage_methods (id,code,name) values (1,'cold','냉장')");
		jdbcTemplate.update(
			"insert into item_statuses (id,code,name) values (1,'active','보유중'),(2,'consumed','소진'),(3,'disposed','폐기')");
	}

	private Long insertInventoryItem(Long userId) {
		long id = nextInventoryItemId++;
		jdbcTemplate.update("""
			insert into inventory_items (id,user_id,custom_name,status_id,unit_id,storage_method_id,
			initial_quantity,remaining_quantity,created_at,updated_at)
			values (?,?,'테스트 재료',1,1,1,10,10,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
			""", id, userId);
		return id;
	}

	private Post createPost(PostStatus status, Long inventoryItemId, boolean deleted) {
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
		return saved;
	}

	private void insertNotification(Long inventoryItemId) {
		jdbcTemplate.update("""
			insert into notifications (user_id, type, inventory_item_id, title, scheduled_at, created_at)
			values (?, 'EXPIRY_ALERT', ?, '알림', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""", USER_ID, inventoryItemId);
	}

	private void insertConsumptionEvent(Long inventoryItemId) {
		jdbcTemplate.update("""
			insert into consumption_events (inventory_item_id, user_id, event_type, event_source_id, quantity, occurred_at, created_at)
			values (?, ?, 'consumed', 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""", inventoryItemId, USER_ID);
	}

	private String bearer(Long userId) {
		return "Bearer " + tokenService.issue(userId).accessToken();
	}

	@Test
	void deletesActiveInventoryItemWithoutHistory() throws Exception {
		Long itemId = insertInventoryItem(USER_ID);

		mockMvc.perform(delete("/api/me/ingredients/" + itemId).header("Authorization", bearer(USER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data").doesNotExist());

		entityManager.flush();
		entityManager.clear();
		Integer count = jdbcTemplate.queryForObject(
			"select count(*) from inventory_items where id = ?", Integer.class, itemId);
		assertThat(count).isZero();
	}

	@Test
	void deletesAssociatedNotificationsTogether() throws Exception {
		Long itemId = insertInventoryItem(USER_ID);
		insertNotification(itemId);

		mockMvc.perform(delete("/api/me/ingredients/" + itemId).header("Authorization", bearer(USER_ID)))
			.andExpect(status().isOk());

		entityManager.flush();
		entityManager.clear();
		Integer count = jdbcTemplate.queryForObject(
			"select count(*) from notifications where inventory_item_id = ?", Integer.class, itemId);
		assertThat(count).isZero();
	}

	@Test
	void returns404ForMissingInventoryItem() throws Exception {
		mockMvc.perform(delete("/api/me/ingredients/999999").header("Authorization", bearer(USER_ID)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void returns404ForOthersInventoryItem() throws Exception {
		Long itemId = insertInventoryItem(OTHER_USER_ID);

		mockMvc.perform(delete("/api/me/ingredients/" + itemId).header("Authorization", bearer(USER_ID)))
			.andExpect(status().isNotFound());
	}

	@Test
	void returns404ForNonActiveInventoryItem() throws Exception {
		Long itemId = insertInventoryItem(USER_ID);
		jdbcTemplate.update("update inventory_items set status_id = 2 where id = ?", itemId);

		mockMvc.perform(delete("/api/me/ingredients/" + itemId).header("Authorization", bearer(USER_ID)))
			.andExpect(status().isNotFound());
	}

	@Test
	void returns409LockedForActiveOpenPost() throws Exception {
		Long itemId = insertInventoryItem(USER_ID);
		createPost(PostStatus.OPEN, itemId, false);

		mockMvc.perform(delete("/api/me/ingredients/" + itemId).header("Authorization", bearer(USER_ID)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("INVENTORY_LOCKED"))
			.andExpect(jsonPath("$.error.message").value("게시물에 올라간 재고는 삭제할 수 없습니다."));
	}

	@Test
	void returns409LockedForActiveReservedPost() throws Exception {
		Long itemId = insertInventoryItem(USER_ID);
		createPost(PostStatus.RESERVED, itemId, false);

		mockMvc.perform(delete("/api/me/ingredients/" + itemId).header("Authorization", bearer(USER_ID)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("INVENTORY_LOCKED"));
	}

	@Test
	void returns409HasHistoryForDeletedPostHistory() throws Exception {
		Long itemId = insertInventoryItem(USER_ID);
		createPost(PostStatus.OPEN, itemId, true);

		mockMvc.perform(delete("/api/me/ingredients/" + itemId).header("Authorization", bearer(USER_ID)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("INVENTORY_HAS_HISTORY"));
	}

	@Test
	void returns409HasHistoryForCompletedPostHistory() throws Exception {
		Long itemId = insertInventoryItem(USER_ID);
		createPost(PostStatus.COMPLETED, itemId, false);

		mockMvc.perform(delete("/api/me/ingredients/" + itemId).header("Authorization", bearer(USER_ID)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("INVENTORY_HAS_HISTORY"));
	}

	@Test
	void returns409HasHistoryForConsumptionEvents() throws Exception {
		Long itemId = insertInventoryItem(USER_ID);
		insertConsumptionEvent(itemId);

		mockMvc.perform(delete("/api/me/ingredients/" + itemId).header("Authorization", bearer(USER_ID)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("INVENTORY_HAS_HISTORY"));
	}

	@Test
	void returns409HasHistoryForTradeReceivedInventory() throws Exception {
		Long itemId = insertInventoryItem(USER_ID);
		LocalDateTime now = LocalDateTime.now();
		Trade trade = Trade.request(999L, USER_ID, 998L, 1000, now);
		trade.accept(now);
		trade.complete(itemId, now);
		tradeRepository.save(trade);

		mockMvc.perform(delete("/api/me/ingredients/" + itemId).header("Authorization", bearer(USER_ID)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("INVENTORY_HAS_HISTORY"));
	}
}
