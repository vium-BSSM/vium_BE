package com.vium.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.auth.service.TokenService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:expiry-notification-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Sql(scripts = "/db/inventory-query-support.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class ExpiryNotificationApiIntegrationTest {

	// Asia/Seoul 기준 2026-11-16 12:00
	private static final Instant NOW = Instant.parse("2026-11-16T03:00:00Z");
	private static final LocalDate TODAY = LocalDate.of(2026, 11, 16);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TokenService tokenService;

	@Autowired
	private JsonMapper mapper;

	@MockitoBean
	private Clock clock;

	@BeforeEach
	void setUp() {
		when(clock.instant()).thenReturn(NOW);
		when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));

		jdbc.update("delete from \"notifications\"");
		jdbc.update("delete from \"consumption_events\"");
		jdbc.update("delete from \"inventory_items\"");
		jdbc.update("delete from \"ingredient_catalog\"");
		jdbc.update("delete from \"user_sessions\"");
		jdbc.update("delete from \"users\"");
		jdbc.update("delete from \"units\"");
		jdbc.update("delete from \"item_statuses\"");
		jdbc.update("delete from \"storage_methods\"");

		jdbc.update("""
			insert into "users" (id, email, display_name, created_at, updated_at)
			values (1, 'expiry1@example.com', '사용자1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
			       (2, 'expiry2@example.com', '사용자2', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""");
		jdbc.update("insert into \"units\" (id, code, name) values (1, 'ea', '개')");
		jdbc.update("""
			insert into "item_statuses" (id, code, name)
			values (1, 'active', '보유중'), (2, 'consumed', '소진'), (3, 'disposed', '폐기'), (4, 'shared', '공유중')
			""");
		jdbc.update("""
			insert into "ingredient_catalog" (id, category_id, default_unit_id, name, created_at)
			values (1, null, 1, '우유', CURRENT_TIMESTAMP)
			""");
	}

	@Test
	void includesOverdueAndTodayAndBoundaryButExcludesPastNPlus1AndOtherStatusesAndOtherUsers() throws Exception {
		insertItem(10, 1, 1, 1L, null, TODAY.minusDays(1)); // 지남 -> daysLeft -1
		insertItem(11, 1, 1, 1L, null, TODAY); // 오늘 -> daysLeft 0
		insertItem(12, 1, 1, 1L, null, TODAY.plusDays(2)); // 기본 expiryAlertDays=2 경계, 포함
		insertItem(13, 1, 1, 1L, null, TODAY.plusDays(3)); // N+1, 제외
		insertItem(14, 1, 1, 1L, null, null); // expiresOn null, 제외
		insertItem(15, 1, 2, 1L, null, TODAY); // consumed, 제외
		insertItem(16, 1, 3, 1L, null, TODAY); // disposed, 제외
		insertItem(17, 2, 1, 1L, null, TODAY); // 다른 사용자, 제외

		mockMvc.perform(get("/api/me/notifications/expiry-popup").header("Authorization", auth(1)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.error").doesNotExist())
			.andExpect(jsonPath("$.data.items.length()").value(3))
			.andExpect(jsonPath("$.data.items[0].inventoryItemId").value(10))
			.andExpect(jsonPath("$.data.items[0].daysLeft").value(-1))
			.andExpect(jsonPath("$.data.items[1].inventoryItemId").value(11))
			.andExpect(jsonPath("$.data.items[1].daysLeft").value(0))
			.andExpect(jsonPath("$.data.items[2].inventoryItemId").value(12))
			.andExpect(jsonPath("$.data.items[2].daysLeft").value(2));
	}

	@Test
	void returnsEmptyArrayWhenNoIngredientIsExpiringSoon() throws Exception {
		mockMvc.perform(get("/api/me/notifications/expiry-widget").header("Authorization", auth(1)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items").isArray())
			.andExpect(jsonPath("$.data.items").isEmpty());
	}

	@Test
	void usesCustomNameWhenRegisteredWithoutCatalog() throws Exception {
		insertItem(10, 1, 1, null, "직접 만든 반찬", TODAY);

		mockMvc.perform(get("/api/me/notifications/expiry-popup").header("Authorization", auth(1)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[0].name").value("직접 만든 반찬"));
	}

	@Test
	void sortsByExpiresOnAscThenInventoryItemIdAsc() throws Exception {
		insertItem(15, 1, 1, 1L, null, TODAY);
		insertItem(12, 1, 1, 1L, null, TODAY);
		insertItem(13, 1, 1, 1L, null, TODAY.minusDays(1));

		mockMvc.perform(get("/api/me/notifications/expiry-popup").header("Authorization", auth(1)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(3))
			.andExpect(jsonPath("$.data.items[0].inventoryItemId").value(13))
			.andExpect(jsonPath("$.data.items[1].inventoryItemId").value(12))
			.andExpect(jsonPath("$.data.items[2].inventoryItemId").value(15));
	}

	@Test
	void expiryAlertDaysChangesTheWindow() throws Exception {
		insertItem(10, 1, 1, 1L, null, TODAY.plusDays(1));
		insertItem(11, 1, 1, 1L, null, TODAY.plusDays(10));

		jdbc.update("update \"users\" set expiry_alert_days = 1 where id = 1");
		mockMvc.perform(get("/api/me/notifications/expiry-popup").header("Authorization", auth(1)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].inventoryItemId").value(10));

		jdbc.update("update \"users\" set expiry_alert_days = 14 where id = 1");
		mockMvc.perform(get("/api/me/notifications/expiry-popup").header("Authorization", auth(1)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(2));
	}

	@Test
	void itemCountMatchesDashboardExpiringSoonCount() throws Exception {
		insertItem(10, 1, 1, 1L, null, TODAY.minusDays(3));
		insertItem(11, 1, 1, 1L, null, TODAY);
		insertItem(12, 1, 1, 1L, null, TODAY.plusDays(2));
		insertItem(13, 1, 1, 1L, null, TODAY.plusDays(3));

		var popup = mockMvc.perform(get("/api/me/notifications/expiry-popup").header("Authorization", auth(1)))
			.andExpect(status().isOk())
			.andReturn();
		var dashboard = mockMvc.perform(get("/api/me/dashboard").header("Authorization", auth(1)))
			.andExpect(status().isOk())
			.andReturn();

		int popupCount = mapper.readTree(popup.getResponse().getContentAsString())
			.path("data").path("items").size();
		int dashboardCount = mapper.readTree(dashboard.getResponse().getContentAsString())
			.path("data").path("expiringSoonCount").asInt();

		assertThat(popupCount).isEqualTo(3);
		assertThat(popupCount).isEqualTo(dashboardCount);
	}

	@Test
	void popupAndWidgetReturnTheSameResult() throws Exception {
		insertItem(10, 1, 1, 1L, null, TODAY);
		insertItem(11, 1, 1, null, "커스텀 재료", TODAY.plusDays(1));

		var popup = mockMvc.perform(get("/api/me/notifications/expiry-popup").header("Authorization", auth(1)))
			.andExpect(status().isOk())
			.andReturn();
		var widget = mockMvc.perform(get("/api/me/notifications/expiry-widget").header("Authorization", auth(1)))
			.andExpect(status().isOk())
			.andReturn();

		assertThat(popup.getResponse().getContentAsString()).isEqualTo(widget.getResponse().getContentAsString());
	}

	@Test
	void requiresAuthentication() throws Exception {
		mockMvc.perform(get("/api/me/notifications/expiry-popup")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/me/notifications/expiry-widget")).andExpect(status().isUnauthorized());
	}

	@Test
	void usesSeoulDateRatherThanUtcDateWhenTheyDiffer() throws Exception {
		// UTC 2026-11-17T15:30:00Z = Asia/Seoul 2026-11-18T00:30 (서울 날짜가 UTC보다 하루 빠름)
		when(clock.instant()).thenReturn(Instant.parse("2026-11-17T15:30:00Z"));
		LocalDate seoulToday = LocalDate.of(2026, 11, 18);

		insertItem(10, 1, 1, 1L, null, seoulToday); // 서울 기준 오늘 -> daysLeft 0 (UTC 기준이면 daysLeft 1)
		insertItem(11, 1, 1, 1L, null, seoulToday.minusDays(1)); // 서울 기준 어제(지남) -> daysLeft -1 (UTC 기준이면 오늘)

		mockMvc.perform(get("/api/me/notifications/expiry-popup").header("Authorization", auth(1)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(2))
			.andExpect(jsonPath("$.data.items[0].inventoryItemId").value(11))
			.andExpect(jsonPath("$.data.items[0].daysLeft").value(-1))
			.andExpect(jsonPath("$.data.items[1].inventoryItemId").value(10))
			.andExpect(jsonPath("$.data.items[1].daysLeft").value(0));
	}

	private void insertItem(long id, long userId, int statusId, Long catalogId, String customName,
			LocalDate expiresOn) {
		jdbc.update("""
			insert into "inventory_items"
			(id, user_id, ingredient_catalog_id, custom_name, status_id, unit_id,
			 initial_quantity, remaining_quantity, purchased_on, expires_on, created_at, updated_at)
			values (?, ?, ?, ?, ?, 1, 2.5, 1.25, DATE '2026-01-01', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""", id, userId, catalogId, customName, statusId, expiresOn);
	}

	private String auth(long userId) {
		return "Bearer " + tokenService.issue(userId).accessToken();
	}
}
