package com.vium.inventory.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.auth.service.TokenService;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {"images.storage.public-base-url=https://images.example.com"})
@AutoConfigureMockMvc
@Transactional
@Sql(scripts = "/db/inventory-query-support.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class IngredientImageIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TokenService tokenService;

	@Autowired
	private jakarta.persistence.EntityManager entityManager;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("""
			insert into users (id, email, display_name, created_at, updated_at)
			values (1, 'image-owner@example.com', '소유자', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
			       (2, 'other-user@example.com', '다른 사용자', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""");
		jdbcTemplate.update("insert into units (id, code, name) values (1, 'ea', '개')");
		jdbcTemplate.update("insert into storage_methods (id, code, name) values (1, 'cold', '냉장')");
		jdbcTemplate.update("insert into item_statuses (id, code, name) values (1, 'active', '보유중'), (4, 'shared', '공유중')");
	}

	@Test
	void register_withOwnIngredientImage_storesImageAndReturnsUrlInListAndDetail() throws Exception {
		long imageId = insertUploadedImage(1L, "INGREDIENT", true);

		String response = mockMvc.perform(authenticatedPost(1L, "/api/me/ingredients", registerBody(imageId)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		long inventoryItemId = extractInventoryItemId(response);

		mockMvc.perform(get("/api/me/ingredients/" + inventoryItemId)
					.header("Authorization", "Bearer " + tokenService.issue(1L).accessToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.imageUrl").value(startsWith("https://images.example.com/images/1/")));

		mockMvc.perform(get("/api/me/ingredients")
					.header("Authorization", "Bearer " + tokenService.issue(1L).accessToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.ingredients[0].imageUrl")
					.value(startsWith("https://images.example.com/images/1/")));
	}

	@Test
	void register_withoutImage_returnsNullImageUrl() throws Exception {
		String response = mockMvc.perform(authenticatedPost(1L, "/api/me/ingredients", registerBody(null)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		long inventoryItemId = extractInventoryItemId(response);

		mockMvc.perform(get("/api/me/ingredients/" + inventoryItemId)
					.header("Authorization", "Bearer " + tokenService.issue(1L).accessToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.imageUrl").value(nullValue()));

		mockMvc.perform(get("/api/me/ingredients")
					.header("Authorization", "Bearer " + tokenService.issue(1L).accessToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.ingredients[0].imageUrl").value(nullValue()));
	}

	@Test
	void register_withOtherUsersImage_returnsNotFoundAndDoesNotCreateIngredient() throws Exception {
		long imageId = insertUploadedImage(2L, "INGREDIENT", true);

		mockMvc.perform(authenticatedPost(1L, "/api/me/ingredients", registerBody(imageId)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("IMAGE_NOT_FOUND"));

		assertThat(jdbcTemplate.queryForObject("select count(*) from inventory_items", Long.class)).isZero();
	}

	@Test
	void register_withWrongPurposeImage_returnsNotFound() throws Exception {
		long imageId = insertUploadedImage(1L, "PROFILE", true);

		mockMvc.perform(authenticatedPost(1L, "/api/me/ingredients", registerBody(imageId)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("IMAGE_NOT_FOUND"));

		assertThat(jdbcTemplate.queryForObject("select count(*) from inventory_items", Long.class)).isZero();
	}

	@Test
	void update_withImageId_changesImage() throws Exception {
		insertInventoryItem(100, 1, null);
		long imageId = insertUploadedImage(1L, "INGREDIENT", true);

		mockMvc.perform(authenticatedPatch(1L, "/api/me/ingredients/100", updateBody(imageId)))
				.andExpect(status().isOk());
		flush();

		mockMvc.perform(get("/api/me/ingredients/100")
					.header("Authorization", "Bearer " + tokenService.issue(1L).accessToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.imageUrl").value(startsWith("https://images.example.com/images/1/")));
	}

	@Test
	void update_withoutImageId_keepsExistingImage() throws Exception {
		long imageId = insertUploadedImage(1L, "INGREDIENT", true);
		insertInventoryItem(100, 1, imageId);

		mockMvc.perform(authenticatedPatch(1L, "/api/me/ingredients/100", updateBody(null)))
				.andExpect(status().isOk());
		flush();

		mockMvc.perform(get("/api/me/ingredients/100")
					.header("Authorization", "Bearer " + tokenService.issue(1L).accessToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.imageUrl").value(startsWith("https://images.example.com/images/1/")));
	}

	@Test
	void update_withOtherUsersImage_returnsNotFoundAndKeepsOriginalImage() throws Exception {
		long originalImageId = insertUploadedImage(1L, "INGREDIENT", true);
		long othersImageId = insertUploadedImage(2L, "INGREDIENT", true);
		insertInventoryItem(100, 1, originalImageId);

		mockMvc.perform(authenticatedPatch(1L, "/api/me/ingredients/100", updateBody(othersImageId)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("IMAGE_NOT_FOUND"));

		assertThat(jdbcTemplate.queryForObject("select image_id from inventory_items where id = 100", Long.class))
			.isEqualTo(originalImageId);
	}

	private MockHttpServletRequestBuilder authenticatedPost(long userId, String url, String body) {
		return post(url).header("Authorization", "Bearer " + tokenService.issue(userId).accessToken())
			.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private MockHttpServletRequestBuilder authenticatedPatch(long userId, String url, String body) {
		return patch(url).header("Authorization", "Bearer " + tokenService.issue(userId).accessToken())
			.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private String registerBody(Long imageId) {
		return """
			{
			  "customName": "이미지 테스트 재료",
			  "quantity": 1,
			  "unitId": 1,
			  "storageMethodId": 1,
			  "purchasedOn": "2026-01-01",
			  "expiresOn": "2026-01-10"
			  %s
			}
			""".formatted(imageId == null ? "" : ", \"imageId\": " + imageId);
	}

	private String updateBody(Long imageId) {
		return """
			{
			  "customName": "수정한 재료",
			  "quantity": 5,
			  "unitId": 1,
			  "storageMethodId": 1,
			  "purchasedOn": "2026-01-01",
			  "expiresOn": "2026-01-10"
			  %s
			}
			""".formatted(imageId == null ? "" : ", \"imageId\": " + imageId);
	}

	private void insertInventoryItem(long id, long userId, Long imageId) {
		jdbcTemplate.update("""
			insert into inventory_items
			(id, user_id, custom_name, status_id, unit_id, storage_method_id,
			 initial_quantity, remaining_quantity, purchased_on, expires_on, image_id, created_at, updated_at)
			values (?, ?, '기존 재료', 1, 1, 1, 10, 10, DATE '2026-01-01', DATE '2026-01-05', ?,
			 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""", id, userId, imageId);
	}

	private long insertUploadedImage(long userId, String purpose, boolean ready) {
		var keyHolder = new GeneratedKeyHolder();
		String objectKey = "images/" + userId + "/" + UUID.randomUUID() + ".png";
		jdbcTemplate.update(connection -> {
			var statement = connection.prepareStatement("""
				insert into uploaded_images (user_id, purpose, bucket, object_key, content_type,
					size_bytes, created_at, ready)
				values (?, ?, 'test-public', ?, 'image/png', 100, CURRENT_TIMESTAMP, ?)
				""", new String[] {"id"});
			statement.setLong(1, userId);
			statement.setString(2, purpose);
			statement.setString(3, objectKey);
			statement.setBoolean(4, ready);
			return statement;
		}, keyHolder);
		return Objects.requireNonNull(keyHolder.getKey()).longValue();
	}

	private void flush() {
		entityManager.flush();
		entityManager.clear();
	}

	private long extractInventoryItemId(String responseBody) {
		var matcher = java.util.regex.Pattern.compile("\"inventoryItemId\":(\\d+)").matcher(responseBody);
		if (!matcher.find()) throw new IllegalStateException("inventoryItemId not found in response: " + responseBody);
		return Long.parseLong(matcher.group(1));
	}
}
