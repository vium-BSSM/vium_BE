package com.vium.post.service;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * 1단계 InventoryLockCheckerIntegrationTest는 재료 수정·소진/폐기 PATCH만 실제 PostInventoryLockChecker로
 * 검증했다(레시피 완료는 거기서 Mockito로 대체됨). 여기서는 레시피 완료까지 실제 구현체로 마저 검증한다.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:recipe-completion-lock-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Transactional
class RecipeCompletionLockIntegrationTest {

	private static final Long USER_ID = 42L;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TokenService tokenService;

	@Autowired
	private PostRepository postRepository;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("insert into units (id,code,name) values (1,'ea','개')");
		jdbcTemplate.update("insert into storage_methods (id,code,name) values (1,'cold','냉장')");
		jdbcTemplate.update(
			"insert into item_statuses (id,code,name) values (1,'active','보유중'),(2,'consumed','소진'),(3,'disposed','폐기'),(4,'shared','나눔·판매')");
		jdbcTemplate.update("insert into event_sources (id,code,name) values (1,'manual','수동')");

		jdbcTemplate.update("""
			insert into inventory_items (id,user_id,custom_name,status_id,unit_id,storage_method_id,
			initial_quantity,remaining_quantity,created_at,updated_at)
			values (400,42,'레시피 대상',1,1,1,10,10,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
			""");

		jdbcTemplate.update(
			"insert into recipes (id,title,category,cook_time,created_at) values (1,'테스트 레시피','KOREAN',10,CURRENT_TIMESTAMP)");
		jdbcTemplate.update("""
			insert into recipe_suggestions (user_id,recipe_id,batch_id,inventory_hash,suggested_at)
			values (42,1,random_uuid(),'test-inventory',CURRENT_TIMESTAMP)
			""");
	}

	private void createOpenPost(Long inventoryItemId) {
		LocalDateTime now = LocalDateTime.now();
		Post post = Post.builder()
			.authorId(USER_ID)
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
		postRepository.save(post);
	}

	private String bearer() {
		return "Bearer " + tokenService.issue(USER_ID).accessToken();
	}

	@Test
	void recipeCompletionSucceedsWhenInventoryHasNoPost() throws Exception {
		mockMvc.perform(post("/api/me/recipes/1/complete")
				.header("Authorization", bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"usages":[{"inventoryId":400,"usedQuantity":1}]}
					"""))
			.andExpect(status().isOk());
	}

	@Test
	void recipeCompletionIsRejectedWhenInventoryHasOpenPost() throws Exception {
		createOpenPost(400L);

		mockMvc.perform(post("/api/me/recipes/1/complete")
				.header("Authorization", bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"usages":[{"inventoryId":400,"usedQuantity":1}]}
					"""))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("INVENTORY_LOCKED"));
	}
}
