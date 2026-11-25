package com.vium.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vium.notification.service.FcmPushClient;
import com.vium.notification.service.PushResult;
import com.vium.notification.service.ShoppingReminderPushService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;

// ExpiryPushServiceIntegrationTest와 같은 Spring 테스트 컨텍스트를 재사용한다.
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:expiry-push-service-it-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@Sql(scripts = "/db/inventory-query-support.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class ShoppingReminderPushServiceIntegrationTest {

	private static final LocalDate SATURDAY = LocalDate.of(2026, 11, 28);
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	private static final Map<String, String> DATA = Map.of("type", "shopping_reminder");

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ShoppingReminderPushService shoppingReminderPushService;

	@MockitoBean
	private Clock clock;

	@MockitoBean
	private FcmPushClient fcmPushClient;

	@BeforeEach
	void setUp() {
		jdbc.update("delete from notifications");
		jdbc.update("delete from consumption_events");
		jdbc.update("delete from posts");
		jdbc.update("delete from inventory_items");
		jdbc.update("delete from user_push_tokens");
		jdbc.update("delete from ingredient_catalog");
		jdbc.update("delete from users");
		jdbc.update("delete from units");
		jdbc.update("delete from item_statuses");

		jdbc.update("insert into units (id, code, name) values (1, 'ea', '개')");
		jdbc.update("insert into item_statuses (id, code, name) values (1, 'active', '보유'), (2, 'consumed', '소진')");
		jdbc.update("""
			insert into users (id, email, display_name, shopping_reminder_enabled, push_enabled, deleted_at,
			 created_at, updated_at)
			values (1, 'shop1@example.com', '사용자1', true, true, null, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
			       (2, 'shop2@example.com', '사용자2', false, true, null, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
			       (3, 'shop3@example.com', '사용자3', true, false, null, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
			       (4, 'shop4@example.com', '사용자4', true, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
			       (5, 'shop5@example.com', '사용자5', true, true, null, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
			       (6, 'shop6@example.com', '사용자6', true, true, null, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""");
		jdbc.update("""
			insert into user_push_tokens (user_id, token, platform, created_at, updated_at)
			values (1, 'token-1', 'ANDROID', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
			       (2, 'token-2', 'ANDROID', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
			       (3, 'token-3', 'ANDROID', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
			       (4, 'token-4', 'ANDROID', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
			       (6, 'token-6', 'ANDROID', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""");
		setToday(SATURDAY);
	}

	@Test
	void sendsOnlyToOptedInUsersWithTokensAndCountsActiveItems() {
		insertItem(10, 1, 1);
		insertItem(11, 1, 1);
		insertItem(12, 1, 2);
		insertItem(13, 2, 1);
		when(fcmPushClient.send(any(), any(), any(), any())).thenReturn(PushResult.SUCCESS);

		assertThat(shoppingReminderPushService.sendShoppingReminderPush()).isEqualTo(2);

		verify(fcmPushClient).send("token-1", "장보기 전 냉장고 확인",
			"냉장고에 재료 2개가 있어요. 장보러 가기 전에 확인해 보세요", DATA);
		verify(fcmPushClient).send("token-6", "장보기 전 냉장고 확인", "장보러 가기 전에 냉장고를 확인해 보세요", DATA);
		verify(fcmPushClient, times(2)).send(any(), any(), any(), any());
		assertThat(rows()).extracting(NotificationRow::userId).containsExactly(1L, 6L);
		assertThat(rows()).allSatisfy(row -> {
			assertThat(row.scheduledAt()).isEqualTo(SATURDAY.atStartOfDay());
			assertThat(row.sentAt()).isNotNull();
		});
	}

	@Test
	void sendsOncePerDayAndAgainOnTheNextRun() {
		jdbc.update("update users set shopping_reminder_enabled = false where id = 6");
		when(fcmPushClient.send(any(), any(), any(), any())).thenReturn(PushResult.SUCCESS);

		assertThat(shoppingReminderPushService.sendShoppingReminderPush()).isEqualTo(1);
		assertThat(shoppingReminderPushService.sendShoppingReminderPush()).isZero();
		assertThat(rows()).hasSize(1);

		setToday(SATURDAY.plusWeeks(1));
		assertThat(shoppingReminderPushService.sendShoppingReminderPush()).isEqualTo(1);
		assertThat(rows()).hasSize(2);
		verify(fcmPushClient, times(2)).send(any(), any(), any(), any());
	}

	@Test
	void retriesTheSameUnsentRowOnTheSameDay() {
		jdbc.update("update users set shopping_reminder_enabled = false where id = 6");
		when(fcmPushClient.send(any(), any(), any(), any())).thenReturn(PushResult.FAILED, PushResult.SUCCESS);

		assertThat(shoppingReminderPushService.sendShoppingReminderPush()).isZero();
		var first = rows().getFirst();
		assertThat(first.sentAt()).isNull();

		assertThat(shoppingReminderPushService.sendShoppingReminderPush()).isEqualTo(1);
		assertThat(rows()).singleElement().satisfies(row -> {
			assertThat(row.id()).isEqualTo(first.id());
			assertThat(row.sentAt()).isNotNull();
		});
	}

	private void setToday(LocalDate day) {
		when(clock.instant()).thenReturn(day.atTime(10, 0).atZone(SEOUL).toInstant());
		when(clock.getZone()).thenReturn(SEOUL);
	}

	private void insertItem(long id, long userId, int statusId) {
		jdbc.update("""
			insert into inventory_items
			(id, user_id, custom_name, status_id, unit_id, initial_quantity, remaining_quantity,
			 created_at, updated_at)
			values (?, ?, '우유', ?, 1, 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""", id, userId, statusId);
	}

	private List<NotificationRow> rows() {
		return jdbc.query("""
			select id, user_id, scheduled_at, sent_at from notifications
			where type = 'shopping_reminder' order by id
			""", (rs, rowNum) -> new NotificationRow(rs.getLong("id"), rs.getLong("user_id"),
			rs.getObject("scheduled_at", LocalDateTime.class), rs.getObject("sent_at", LocalDateTime.class)));
	}

	private record NotificationRow(long id, long userId, LocalDateTime scheduledAt, LocalDateTime sentAt) {
	}
}
