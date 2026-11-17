package com.vium.post.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.auth.service.TokenService;
import java.math.BigDecimal;
import java.time.LocalDate;
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
class PostRegisterControllerIntegrationTest {

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
	private jakarta.persistence.EntityManager entityManager;

	private long nextInventoryItemId = 1;
	private long nextCatalogId = 1;
	private long nextImageId = 1;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("insert into units (id,code,name) values (1,'ea','개'),(2,'g','그램'),(3,'kg','킬로그램')");
		jdbcTemplate.update("insert into storage_methods (id,code,name) values (1,'cold','냉장'),(2,'frozen','냉동'),(3,'room','실온')");
		jdbcTemplate.update(
			"insert into item_statuses (id,code,name) values (1,'active','보유중'),(2,'consumed','소진'),(3,'disposed','폐기'),(4,'shared','나눔·판매')");
		jdbcTemplate.update("insert into event_sources (id,code,name) values (1,'manual','수동')");
		jdbcTemplate.update("delete from ingredient_categories");
		jdbcTemplate.update("insert into ingredient_categories (id,name) values (1,'유제품')");
		jdbcTemplate.update("delete from users");
		jdbcTemplate.update("""
			insert into users (id, email, display_name, created_at, updated_at)
			values (42, 'owner@example.com', '주인', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
			       (43, 'other@example.com', '남', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""");
	}

	private Long insertCatalog(boolean postAllowed) {
		long id = nextCatalogId++;
		jdbcTemplate.update("""
			insert into ingredient_catalog (id, category_id, default_unit_id, name, post_allowed, created_at)
			values (?, 1, 3, '테스트재료', ?, CURRENT_TIMESTAMP)
			""", id, postAllowed);
		return id;
	}

	private void insertBaseValue(short categoryId, short unitId, String baseValue) {
		jdbcTemplate.update(
			"insert into credit_base_values (category_id, unit_id, base_value) values (?, ?, ?)",
			categoryId, unitId, new BigDecimal(baseValue));
	}

	private Long insertInventoryItem(Long userId, Long catalogId, String quantity, LocalDate expiresOn) {
		long id = nextInventoryItemId++;
		jdbcTemplate.update("""
			insert into inventory_items (id,user_id,ingredient_catalog_id,status_id,unit_id,
			storage_method_id,initial_quantity,remaining_quantity,expires_on,created_at,updated_at)
			values (?,?,?,(select id from item_statuses where code = 'active'),3,1,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
			""", id, userId, catalogId, new BigDecimal(quantity), new BigDecimal(quantity), expiresOn);
		return id;
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

	private LocalDate today() {
		return LocalDate.now(SEOUL);
	}

	private String baseRequest(Long inventoryItemId, String type, String title, Integer price, String imageIds,
			short storageMethodId) {
		return """
			{"inventoryItemId":%d,"type":"%s","title":"%s","price":%s,"imageIds":%s,"isOpened":true,
			 "storageMethodId":%d,"latitude":37.5,"longitude":127.0}
			""".formatted(inventoryItemId, type, title, price == null ? "null" : price, imageIds, storageMethodId);
	}

	@Test
	void registersShareWithoutPrice() throws Exception {
		Long catalogId = insertCatalog(true);
		Long itemId = insertInventoryItem(USER_ID, catalogId, "1", today().plusDays(10));

		mockMvc.perform(post("/api/me/posts").header("Authorization", bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content(baseRequest(itemId, "SHARE", "나눔합니다", null, "[]", (short) 2)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("OPEN"));

		entityManager.flush();
		entityManager.clear();
		Short storageMethodId = jdbcTemplate.queryForObject(
			"select storage_method_id from inventory_items where id = ?", Short.class, itemId);
		assertThat(storageMethodId).isEqualTo((short) 2);
	}

	@Test
	void registersSaleWithinPriceRange() throws Exception {
		Long catalogId = insertCatalog(true);
		insertBaseValue((short) 1, (short) 3, "1000.000");
		Long itemId = insertInventoryItem(USER_ID, catalogId, "2", today().plusDays(10));
		// recommendedPrice = 1000 * 2 * 1.0 = 2000, range [1600, 2400]

		mockMvc.perform(post("/api/me/posts").header("Authorization", bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content(baseRequest(itemId, "SALE", "팝니다", 2000, "[]", (short) 1)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("OPEN"));

		Integer recommendedPrice = jdbcTemplate.queryForObject(
			"select recommended_price from posts where inventory_item_id = ?", Integer.class, itemId);
		assertThat(recommendedPrice).isEqualTo(2000);
	}

	@Test
	void rejectsSalePriceBelowMinimum() throws Exception {
		Long catalogId = insertCatalog(true);
		insertBaseValue((short) 1, (short) 3, "1000.000");
		Long itemId = insertInventoryItem(USER_ID, catalogId, "2", today().plusDays(10));

		mockMvc.perform(post("/api/me/posts").header("Authorization", bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content(baseRequest(itemId, "SALE", "팝니다", 1599, "[]", (short) 1)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("PRICE_OUT_OF_RANGE"));
	}

	@Test
	void rejectsSalePriceAboveMaximum() throws Exception {
		Long catalogId = insertCatalog(true);
		insertBaseValue((short) 1, (short) 3, "1000.000");
		Long itemId = insertInventoryItem(USER_ID, catalogId, "2", today().plusDays(10));

		mockMvc.perform(post("/api/me/posts").header("Authorization", bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content(baseRequest(itemId, "SALE", "팝니다", 2401, "[]", (short) 1)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("PRICE_OUT_OF_RANGE"));
	}

	@Test
	void acceptsSalePriceAtRangeBoundaries() throws Exception {
		Long catalogId = insertCatalog(true);
		insertBaseValue((short) 1, (short) 3, "1000.000");
		Long minItem = insertInventoryItem(USER_ID, catalogId, "2", today().plusDays(10));
		mockMvc.perform(post("/api/me/posts").header("Authorization", bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content(baseRequest(minItem, "SALE", "팝니다", 1600, "[]", (short) 1)))
			.andExpect(status().isOk());

		Long maxItem = insertInventoryItem(USER_ID, catalogId, "2", today().plusDays(10));
		mockMvc.perform(post("/api/me/posts").header("Authorization", bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content(baseRequest(maxItem, "SALE", "팝니다", 2400, "[]", (short) 1)))
			.andExpect(status().isOk());
	}

	@Test
	void rejectsSaleWithoutPriceBasis() throws Exception {
		Long catalogId = insertCatalog(true);
		Long itemId = insertInventoryItem(USER_ID, catalogId, "1", today().plusDays(10));

		mockMvc.perform(post("/api/me/posts").header("Authorization", bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content(baseRequest(itemId, "SALE", "팝니다", 1000, "[]", (short) 1)))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.error.code").value("POST_NOT_ALLOWED"))
			.andExpect(jsonPath("$.error.message").value("이 재료는 나눔으로만 올릴 수 있습니다."));
	}

	@Test
	void rejectsShareWithNonZeroPrice() throws Exception {
		Long catalogId = insertCatalog(true);
		Long itemId = insertInventoryItem(USER_ID, catalogId, "1", today().plusDays(10));

		mockMvc.perform(post("/api/me/posts").header("Authorization", bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content(baseRequest(itemId, "SHARE", "나눔", 500, "[]", (short) 1)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	void rejectsSaleWithoutPrice() throws Exception {
		Long catalogId = insertCatalog(true);
		insertBaseValue((short) 1, (short) 3, "1000.000");
		Long itemId = insertInventoryItem(USER_ID, catalogId, "2", today().plusDays(10));

		mockMvc.perform(post("/api/me/posts").header("Authorization", bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content(baseRequest(itemId, "SALE", "팝니다", null, "[]", (short) 1)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	void returns404ForMissingInventoryItem() throws Exception {
		mockMvc.perform(post("/api/me/posts").header("Authorization", bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content(baseRequest(999999L, "SHARE", "나눔", null, "[]", (short) 1)))
			.andExpect(status().isNotFound());
	}

	@Test
	void returns404ForOthersInventoryItem() throws Exception {
		Long catalogId = insertCatalog(true);
		Long itemId = insertInventoryItem(OTHER_USER_ID, catalogId, "1", today().plusDays(10));

		mockMvc.perform(post("/api/me/posts").header("Authorization", bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content(baseRequest(itemId, "SHARE", "나눔", null, "[]", (short) 1)))
			.andExpect(status().isNotFound());
	}

	@Test
	void returns422WithExactMessageForExpiresTooSoon() throws Exception {
		Long catalogId = insertCatalog(true);
		Long itemId = insertInventoryItem(USER_ID, catalogId, "1", today());

		mockMvc.perform(post("/api/me/posts").header("Authorization", bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content(baseRequest(itemId, "SHARE", "나눔", null, "[]", (short) 1)))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.error.code").value("POST_NOT_ALLOWED"))
			.andExpect(jsonPath("$.error.message").value("소비기한이 1일 이상 남은 재료만 게시할 수 있습니다."));
	}

	@Test
	void rejectsMoreThanFiveImages() throws Exception {
		Long catalogId = insertCatalog(true);
		Long itemId = insertInventoryItem(USER_ID, catalogId, "1", today().plusDays(10));

		mockMvc.perform(post("/api/me/posts").header("Authorization", bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content(baseRequest(itemId, "SHARE", "나눔", null, "[1,2,3,4,5,6]", (short) 1)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	void rejectsDuplicateImageIds() throws Exception {
		Long catalogId = insertCatalog(true);
		Long itemId = insertInventoryItem(USER_ID, catalogId, "1", today().plusDays(10));
		Long imageId = insertPostImage(USER_ID);

		mockMvc.perform(post("/api/me/posts").header("Authorization", bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content(baseRequest(itemId, "SHARE", "나눔", null, "[%d,%d]".formatted(imageId, imageId), (short) 1)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	void rejectsImageNotOwnedByCaller() throws Exception {
		Long catalogId = insertCatalog(true);
		Long itemId = insertInventoryItem(USER_ID, catalogId, "1", today().plusDays(10));
		Long otherUsersImageId = insertPostImage(OTHER_USER_ID);

		mockMvc.perform(post("/api/me/posts").header("Authorization", bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content(baseRequest(itemId, "SHARE", "나눔", null, "[%d]".formatted(otherUsersImageId), (short) 1)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.error.message").value("내가 올린 게시물 이미지만 사용할 수 있습니다."));
	}

	@Test
	void rejectsMissingImageId() throws Exception {
		Long catalogId = insertCatalog(true);
		Long itemId = insertInventoryItem(USER_ID, catalogId, "1", today().plusDays(10));

		mockMvc.perform(post("/api/me/posts").header("Authorization", bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content(baseRequest(itemId, "SHARE", "나눔", null, "[999999]", (short) 1)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.error.message").value("내가 올린 게시물 이미지만 사용할 수 있습니다."));
	}

	@Test
	void rejectsImageWithWrongPurpose() throws Exception {
		Long catalogId = insertCatalog(true);
		Long itemId = insertInventoryItem(USER_ID, catalogId, "1", today().plusDays(10));
		long wrongPurposeImageId = nextImageId++;
		jdbcTemplate.update("""
			insert into uploaded_images (id, user_id, purpose, bucket, object_key, content_type, size_bytes, ready, created_at)
			values (?, ?, 'INGREDIENT', 'public', ?, 'image/jpeg', 100, true, CURRENT_TIMESTAMP)
			""", wrongPurposeImageId, USER_ID, "images/" + USER_ID + "/" + wrongPurposeImageId + ".jpg");

		mockMvc.perform(post("/api/me/posts").header("Authorization", bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content(baseRequest(itemId, "SHARE", "나눔", null, "[%d]".formatted(wrongPurposeImageId), (short) 1)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.error.message").value("내가 올린 게시물 이미지만 사용할 수 있습니다."));
	}

	@Test
	void registersWithValidImagesInThumbnailOrder() throws Exception {
		Long catalogId = insertCatalog(true);
		Long itemId = insertInventoryItem(USER_ID, catalogId, "1", today().plusDays(10));
		Long first = insertPostImage(USER_ID);
		Long second = insertPostImage(USER_ID);

		mockMvc.perform(post("/api/me/posts").header("Authorization", bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content(baseRequest(itemId, "SHARE", "나눔", null, "[%d,%d]".formatted(first, second), (short) 1)))
			.andExpect(status().isOk());

		Integer thumbnailSortOrderImage = jdbcTemplate.queryForObject(
			"select image_id from post_images where post_id = (select id from posts where inventory_item_id = ?) and sort_order = 0",
			Integer.class, itemId);
		assertThat(thumbnailSortOrderImage.longValue()).isEqualTo(first);
	}

	@Test
	void rejectsBlankTitle() throws Exception {
		Long catalogId = insertCatalog(true);
		Long itemId = insertInventoryItem(USER_ID, catalogId, "1", today().plusDays(10));

		mockMvc.perform(post("/api/me/posts").header("Authorization", bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content(baseRequest(itemId, "SHARE", "   ", null, "[]", (short) 1)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	void rejectsOutOfRangeLatitude() throws Exception {
		Long catalogId = insertCatalog(true);
		Long itemId = insertInventoryItem(USER_ID, catalogId, "1", today().plusDays(10));

		mockMvc.perform(post("/api/me/posts").header("Authorization", bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"inventoryItemId":%d,"type":"SHARE","title":"나눔","imageIds":[],"isOpened":true,
					 "storageMethodId":1,"latitude":95.0,"longitude":127.0}
					""".formatted(itemId)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	void reregisteringSameInventoryAfterDeleteSucceeds() throws Exception {
		Long catalogId = insertCatalog(true);
		Long itemId = insertInventoryItem(USER_ID, catalogId, "1", today().plusDays(10));

		mockMvc.perform(post("/api/me/posts").header("Authorization", bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content(baseRequest(itemId, "SHARE", "나눔", null, "[]", (short) 1)))
			.andExpect(status().isOk());

		jdbcTemplate.update("update posts set deleted_at = CURRENT_TIMESTAMP where inventory_item_id = ?", itemId);

		mockMvc.perform(post("/api/me/posts").header("Authorization", bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content(baseRequest(itemId, "SHARE", "다시나눔", null, "[]", (short) 1)))
			.andExpect(status().isOk());
	}
}
