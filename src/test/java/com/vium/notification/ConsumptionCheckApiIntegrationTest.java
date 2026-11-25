package com.vium.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.auth.service.TokenService;
import java.time.Clock;
import java.time.Instant;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

// ExpiryNotificationApiIntegrationTest와 같은 Spring 테스트 컨텍스트를 재사용한다.
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:expiry-notification-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Sql(scripts = "/db/inventory-query-support.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class ConsumptionCheckApiIntegrationTest {

	private static final String URL = "/api/me/notifications/consumption-check";
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	private static final Instant NOW = Instant.parse("2026-11-24T15:30:00Z");
	private static final LocalDate TODAY = LocalDate.of(2026, 11, 25);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TokenService tokenService;

	@MockitoBean
	private Clock clock;

	@BeforeEach
	void setUp() {
		when(clock.instant()).thenReturn(NOW);
		when(clock.getZone()).thenReturn(SEOUL);

		jdbc.update("delete from notifications");
		jdbc.update("delete from consumption_events");
		jdbc.update("delete from posts");
		jdbc.update("delete from inventory_items");
		jdbc.update("delete from ingredient_catalog");
		jdbc.update("delete from user_sessions");
		jdbc.update("delete from users");
		jdbc.update("delete from units");
		jdbc.update("delete from item_statuses");
		jdbc.update("delete from storage_methods");
		jdbc.update("delete from event_sources");

		jdbc.update("""
			insert into users (id, email, display_name, auto_dispose_enabled, push_enabled, created_at, updated_at)
			values (1, 'keep1@example.com', '사용자1', false, false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
			       (2, 'keep2@example.com', '사용자2', true, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""");
		jdbc.update("insert into units (id, code, name) values (1, 'l', 'L')");
		jdbc.update("insert into storage_methods (id, code, name) values (1, 'refrigerated', '냉장')");
		jdbc.update("insert into item_statuses (id, code, name) values (1, 'active', '보유'), (2, 'consumed', '소진')");
		jdbc.update("insert into event_sources (id, code, name) values (1, 'manual', '수동')");
		jdbc.update("""
			insert into ingredient_catalog (id, default_unit_id, name, created_at)
			values (1, 1, '우유', CURRENT_TIMESTAMP)
			""");
	}

	@Test
	void listsOwnedUnconfirmedItemsDueByTodayInDateAndIdOrder() throws Exception {
		insertItem(12, 1, 1, TODAY);
		insertItem(10, 1, 1, TODAY.minusDays(1));
		insertItem(11, 1, 1, TODAY);
		insertItem(13, 1, 1, TODAY.plusDays(1));
		insertItem(14, 1, 1, null);
		insertItem(15, 1, 2, TODAY);
		insertItem(16, 2, 1, TODAY);
		insertItem(17, 1, 1, TODAY);
		insertItem(18, 1, 1, TODAY);
		jdbc.update("update inventory_items set ingredient_catalog_id = 1 where id = 10");
		jdbc.update("update inventory_items set remaining_quantity = 0 where id = 17");
		jdbc.update("update inventory_items set keep_confirmed_expires_on = expires_on where id = 18");

		list().andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.items[*].inventoryItemId", contains(10, 11, 12)))
			.andExpect(jsonPath("$.data.items[0].name").value("우유"))
			.andExpect(jsonPath("$.data.items[0].remainingQuantity").value(0.5))
			.andExpect(jsonPath("$.data.items[0].unit").value("L"))
			.andExpect(jsonPath("$.data.items[1].name").value("직접 만든 반찬"))
			.andExpect(jsonPath("$.data.items[1].expiresOn").value(TODAY.toString()));
	}

	@Test
	void excludesItemsWithOpenOrReservedPostsButIncludesDeletedOrCompletedPosts() throws Exception {
		for (long itemId = 20; itemId <= 23; itemId++) {
			insertItem(itemId, 1, 1, TODAY);
		}
		jdbc.update("""
			insert into posts (author_id, inventory_item_id, type, status, title, description, price,
			 is_opened, latitude, longitude, like_count, created_at, updated_at, deleted_at)
			values (1, 20, 'SHARE', 'OPEN', '제목', '', 0, false, 37.5, 127.0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, null),
			       (1, 21, 'SHARE', 'RESERVED', '제목', '', 0, false, 37.5, 127.0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, null),
			       (1, 22, 'SHARE', 'OPEN', '제목', '', 0, false, 37.5, 127.0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
			       (1, 23, 'SHARE', 'COMPLETED', '제목', '', 0, false, 37.5, 127.0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, null)
			""");

		list().andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[*].inventoryItemId", contains(22, 23)));
	}

	@Test
	void keepsItemOnExpirationDayIdempotentlyAndMarksOnlyItsConsumeCheckNotificationRead() throws Exception {
		insertItem(11, 1, 1, TODAY);
		jdbc.update("""
			insert into notifications (user_id, inventory_item_id, type, title, scheduled_at, created_at)
			values (1, 11, 'consume_check', '소진 확인', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
			       (1, 11, 'expiry', '임박 알림', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""");

		keep(11, 1).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.inventoryItemId").value(11))
			.andExpect(jsonPath("$.data.keepConfirmedExpiresOn").value(TODAY.toString()));
		LocalDateTime firstReadAt = readAt("consume_check");
		assertThat(firstReadAt).isEqualTo(LocalDateTime.ofInstant(NOW, SEOUL));
		assertThat(readAt("expiry")).isNull();

		when(clock.instant()).thenReturn(NOW.plusSeconds(60));
		keep(11, 1).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.keepConfirmedExpiresOn").value(TODAY.toString()));
		assertThat(readAt("consume_check")).isEqualTo(firstReadAt);
		assertThat(jdbc.queryForObject("""
			select keep_confirmed_expires_on from inventory_items where id = 11
			""", LocalDate.class)).isEqualTo(TODAY);

		list().andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
	}

	@Test
	void becomesEligibleAgainAfterEditingTheExpirationDateEvenWithoutAnExistingNotification() throws Exception {
		LocalDate firstExpiration = TODAY.minusDays(2);
		insertItem(11, 1, 1, firstExpiration);
		keep(11, 1).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.keepConfirmedExpiresOn").value(firstExpiration.toString()));
		list().andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());

		mockMvc.perform(patch("/api/me/ingredients/11").header("Authorization", auth(1))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"customName":"직접 만든 반찬","quantity":1,"unitId":1,"storageMethodId":1,
					 "purchasedOn":"2026-01-01","expiresOn":"%s"}
					""".formatted(TODAY)))
			.andExpect(status().isOk());

		list().andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[*].inventoryItemId", contains(11)))
			.andExpect(jsonPath("$.data.items[0].expiresOn").value(TODAY.toString()));
	}

	@Test
	void rejectsUnknownOrUnownedItems() throws Exception {
		insertItem(11, 2, 1, TODAY);

		keep(11, 1).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
		keep(12, 1).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void rejectsFutureUndatedAndNonActiveItems() throws Exception {
		insertItem(11, 1, 1, TODAY.plusDays(1));
		insertItem(12, 1, 1, null);
		insertItem(13, 1, 2, TODAY);

		for (long id : new long[] {11, 12, 13}) {
			keep(id, 1).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
		}
	}

	@Test
	void existingConsumedActionRecordsOneEventRemovesItemAndMarksConsumeCheckRead() throws Exception {
		insertItem(11, 1, 1, TODAY);
		jdbc.update("""
			insert into notifications (user_id, inventory_item_id, type, title, scheduled_at, created_at)
			values (1, 11, 'consume_check', '소진 확인', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
			       (1, 11, 'expiry', '임박 알림', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""");
		list().andExpect(jsonPath("$.data.items[*].inventoryItemId", contains(11)));

		mockMvc.perform(patch("/api/me/ingredients/11/status").header("Authorization", auth(1))
				.contentType(MediaType.APPLICATION_JSON).content("""
					{"status":"consumed","quantity":0.5}
					"""))
			.andExpect(status().isOk());

		list().andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
		assertThat(jdbc.queryForList("select event_type from consumption_events where inventory_item_id = 11",
			String.class)).containsExactly("consumed");
		assertThat(readAt("consume_check")).isEqualTo(LocalDateTime.ofInstant(NOW, SEOUL));
		assertThat(readAt("expiry")).isNull();
	}

	@Test
	void requiresAuthenticationForBothEndpoints() throws Exception {
		mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
		mockMvc.perform(post(URL + "/11/keep")).andExpect(status().isUnauthorized());
	}

	private void insertItem(long id, long userId, int statusId, LocalDate expiresOn) {
		jdbc.update("""
			insert into inventory_items
			(id, user_id, custom_name, status_id, unit_id, storage_method_id,
			 initial_quantity, remaining_quantity, purchased_on, expires_on, created_at, updated_at)
			values (?, ?, '직접 만든 반찬', ?, 1, 1, 0.5, 0.5, DATE '2026-01-01', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""", id, userId, statusId, expiresOn);
	}

	private LocalDateTime readAt(String type) {
		return jdbc.queryForObject("select read_at from notifications where type = ?", LocalDateTime.class, type);
	}

	private ResultActions list() throws Exception {
		return mockMvc.perform(get(URL).header("Authorization", auth(1)));
	}

	private ResultActions keep(long itemId, long userId) throws Exception {
		return mockMvc.perform(post(URL + "/{ingredientId}/keep", itemId).header("Authorization", auth(userId)));
	}

	private String auth(long userId) {
		return "Bearer " + tokenService.issue(userId).accessToken();
	}
}
