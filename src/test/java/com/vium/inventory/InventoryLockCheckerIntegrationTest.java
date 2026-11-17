package com.vium.inventory;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.auth.service.TokenService;
import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.inventory.service.InventoryLockChecker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * post 도메인이 나중에 구현할 InventoryLockChecker를 가짜로 등록해, 게시(공유) 중인 재고의
 * 수정·소진/폐기 PATCH·레시피 완료(usageRate 0 포함)가 모두 INVENTORY_LOCKED(409)로 막히는지 검증한다.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:inventory-lock-checker;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Transactional
class InventoryLockCheckerIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TokenService tokenService;

	@MockitoBean
	private InventoryLockChecker lockChecker;

	@BeforeEach
	void setUp() {
		doNothing().when(lockChecker).assertNotLocked(org.mockito.ArgumentMatchers.anyLong());

		jdbcTemplate.update("insert into units (id,code,name) values (1,'ea','개')");
		jdbcTemplate.update("insert into storage_methods (id,code,name) values (1,'cold','냉장')");
		jdbcTemplate.update(
			"insert into item_statuses (id,code,name) values (1,'active','보유중'),(2,'consumed','소진'),(3,'disposed','폐기'),(4,'shared','공유중')");
		jdbcTemplate.update("insert into event_sources (id,code,name) values (1,'manual','수동')");

		jdbcTemplate.update("""
			insert into inventory_items (id,user_id,custom_name,status_id,unit_id,storage_method_id,
			initial_quantity,remaining_quantity,created_at,updated_at)
			values (100,42,'업데이트 대상',1,1,1,10,10,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
			       (200,42,'소진폐기 대상',1,1,1,10,10,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
			       (300,42,'레시피 사용량0',1,1,1,10,10,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
			       (301,42,'레시피 일반사용',1,1,1,10,10,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
			""");

		jdbcTemplate.update(
			"insert into recipes (id,title,category,cook_time,created_at) values (1,'테스트 레시피','KOREAN',10,CURRENT_TIMESTAMP)");
		jdbcTemplate.update("""
			insert into recipe_suggestions (user_id,recipe_id,batch_id,inventory_hash,suggested_at)
			values (42,1,random_uuid(),'test-inventory',CURRENT_TIMESTAMP)
			""");
	}

	@Test
	void updateSucceedsWhenNotLocked() throws Exception {
		mockMvc.perform(patch("/api/me/ingredients/100")
				.header("Authorization", bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"customName":"수정됨","quantity":10,"unitId":1,"storageMethodId":1,
					 "purchasedOn":"2026-01-01","expiresOn":"2026-02-01"}
					"""))
			.andExpect(status().isOk());
	}

	@Test
	void updateIsRejectedWhenLocked() throws Exception {
		doThrow(new BusinessException(ErrorCode.INVENTORY_LOCKED, "게시물에 올라간 재고는 사용할 수 없습니다."))
			.when(lockChecker).assertNotLocked(eq(100L));

		mockMvc.perform(patch("/api/me/ingredients/100")
				.header("Authorization", bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"customName":"수정됨","quantity":10,"unitId":1,"storageMethodId":1,
					 "purchasedOn":"2026-01-01","expiresOn":"2026-02-01"}
					"""))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("INVENTORY_LOCKED"))
			.andExpect(jsonPath("$.error.message").value("게시물에 올라간 재고는 사용할 수 없습니다."));
	}

	@Test
	void disposePatchSucceedsWhenNotLocked() throws Exception {
		mockMvc.perform(patch("/api/me/ingredients/200/status")
				.header("Authorization", bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"status":"consumed","quantity":2}
					"""))
			.andExpect(status().isOk());
	}

	@Test
	void disposePatchIsRejectedWhenLocked() throws Exception {
		doThrow(new BusinessException(ErrorCode.INVENTORY_LOCKED, "게시물에 올라간 재고는 사용할 수 없습니다."))
			.when(lockChecker).assertNotLocked(eq(200L));

		mockMvc.perform(patch("/api/me/ingredients/200/status")
				.header("Authorization", bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"status":"consumed","quantity":2}
					"""))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("INVENTORY_LOCKED"));
	}

	@Test
	void recipeCompletionSucceedsWhenNotLocked() throws Exception {
		mockMvc.perform(post("/api/me/recipes/1/complete")
				.header("Authorization", bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"usages":[{"inventoryId":300,"usageRate":0},{"inventoryId":301,"usedQuantity":1}]}
					"""))
			.andExpect(status().isOk());
	}

	@Test
	void recipeCompletionIsRejectedWhenZeroUsageRateInventoryIsLocked() throws Exception {
		// usageRate=0이라 차감이 전혀 없는 재고라도 잠겨 있으면 레시피 완료 전체가 막혀야 한다.
		doThrow(new BusinessException(ErrorCode.INVENTORY_LOCKED, "게시물에 올라간 재고는 사용할 수 없습니다."))
			.when(lockChecker).assertNotLocked(eq(300L));

		mockMvc.perform(post("/api/me/recipes/1/complete")
				.header("Authorization", bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"usages":[{"inventoryId":300,"usageRate":0},{"inventoryId":301,"usedQuantity":1}]}
					"""))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("INVENTORY_LOCKED"));
	}

	private String bearer() {
		return "Bearer " + tokenService.issue(42L).accessToken();
	}
}
