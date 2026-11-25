package com.vium.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vium.notification.service.ConsumeCheckPushService;
import com.vium.notification.service.FcmPushClient;
import com.vium.notification.service.PushResult;
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
class ConsumeCheckPushServiceIntegrationTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 11, 25);
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ConsumeCheckPushService consumeCheckPushService;

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
			insert into users (id, email, display_name, push_enabled, deleted_at, created_at, updated_at)
			values (1, 'check1@example.com', '사용자1', true, null, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
			       (2, 'check2@example.com', '사용자2', false, null, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
			       (3, 'check3@example.com', '사용자3', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
			       (4, 'check4@example.com', '사용자4', true, null, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""");
		jdbc.update("""
			insert into user_push_tokens (user_id, token, platform, created_at, updated_at)
			values (1, 'token-1', 'ANDROID', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
			       (2, 'token-2', 'ANDROID', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
			       (3, 'token-3', 'ANDROID', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""");
		setToday(TODAY);
	}

	@Test
	void sendsOnlyForActiveUnconfirmedPositiveQuantityItemsExpiringTodayAndEligibleUsers() {
		insertItem(10, 1, 1, 1, TODAY, null);
		insertItem(11, 1, 1, 1, TODAY.minusDays(1), null);
		insertItem(12, 1, 1, 1, TODAY.plusDays(1), null);
		insertItem(13, 1, 1, 0, TODAY, null);
		insertItem(14, 1, 1, 1, TODAY, TODAY);
		insertItem(15, 1, 2, 1, TODAY, null);
		insertItem(16, 2, 1, 1, TODAY, null);
		insertItem(17, 3, 1, 1, TODAY, null);
		insertItem(18, 4, 1, 1, TODAY, null);
		insertItem(19, 1, 1, 1, null, null);
		when(fcmPushClient.send(any(), any(), any(), any())).thenReturn(PushResult.SUCCESS);

		assertThat(consumeCheckPushService.sendDailyConsumeCheckPush()).isEqualTo(1);

		assertThat(rows()).singleElement().satisfies(row -> {
			assertThat(row.inventoryItemId()).isEqualTo(10L);
			assertThat(row.sentAt()).isNotNull();
		});
		verify(fcmPushClient).send("token-1", "소진 확인", "우유, 다 드셨어요?", Map.of("type", "consume_check"));
	}

	@Test
	void excludesItemsWithOpenOrReservedPostsButIncludesDeletedOrCompletedPosts() {
		for (long itemId = 20; itemId <= 23; itemId++) {
			insertItem(itemId, 1, 1, 1, TODAY, null);
		}
		jdbc.update("""
			insert into posts (author_id, inventory_item_id, type, status, title, description, price,
			 is_opened, latitude, longitude, like_count, created_at, updated_at, deleted_at)
			values (1, 20, 'SHARE', 'OPEN', '제목', '', 0, false, 37.5, 127.0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, null),
			       (1, 21, 'SHARE', 'RESERVED', '제목', '', 0, false, 37.5, 127.0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, null),
			       (1, 22, 'SHARE', 'OPEN', '제목', '', 0, false, 37.5, 127.0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
			       (1, 23, 'SHARE', 'COMPLETED', '제목', '', 0, false, 37.5, 127.0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, null)
			""");
		when(fcmPushClient.send(any(), any(), any(), any())).thenReturn(PushResult.SUCCESS);

		assertThat(consumeCheckPushService.sendDailyConsumeCheckPush()).isEqualTo(1);

		assertThat(rows()).extracting(NotificationRow::inventoryItemId).containsExactly(22L, 23L);
		verify(fcmPushClient).send("token-1", "소진 확인", "오늘까지인 재료 2개, 다 드셨나요?", Map.of("type", "consume_check"));
	}

	@Test
	void sendsOnlyOnceEvenIfExpirationDateChangesAfterFirstSend() {
		insertItem(10, 1, 1, 1, TODAY, null);
		when(fcmPushClient.send(any(), any(), any(), any())).thenReturn(PushResult.SUCCESS);

		assertThat(consumeCheckPushService.sendDailyConsumeCheckPush()).isEqualTo(1);
		var sent = rows().getFirst();
		assertThat(consumeCheckPushService.sendDailyConsumeCheckPush()).isZero();

		LocalDate nextDay = TODAY.plusDays(1);
		jdbc.update("update inventory_items set expires_on = ? where id = 10", nextDay);
		setToday(nextDay);
		assertThat(consumeCheckPushService.sendDailyConsumeCheckPush()).isZero();

		assertThat(rows()).containsExactly(sent);
		verify(fcmPushClient, times(1)).send(any(), any(), any(), any());
	}

	@Test
	void retriesTheSameUnsentRowDuringTheExpirationDay() {
		insertItem(10, 1, 1, 1, TODAY, null);
		when(fcmPushClient.send(any(), any(), any(), any())).thenReturn(PushResult.FAILED, PushResult.SUCCESS);

		assertThat(consumeCheckPushService.sendDailyConsumeCheckPush()).isZero();
		assertThat(rows()).hasSize(1);
		var first = rows().getFirst();
		assertThat(first.sentAt()).isNull();

		assertThat(consumeCheckPushService.sendDailyConsumeCheckPush()).isEqualTo(1);
		assertThat(rows()).singleElement().satisfies(row -> {
			assertThat(row.id()).isEqualTo(first.id());
			assertThat(row.sentAt()).isNotNull();
		});
		verify(fcmPushClient, times(2)).send(any(), any(), any(), any());
	}

	private void setToday(LocalDate day) {
		when(clock.instant()).thenReturn(day.atTime(20, 0).atZone(SEOUL).toInstant());
		when(clock.getZone()).thenReturn(SEOUL);
	}

	private void insertItem(long id, long userId, int statusId, int remainingQuantity,
			LocalDate expiresOn, LocalDate keepConfirmedExpiresOn) {
		jdbc.update("""
			insert into inventory_items
			(id, user_id, custom_name, status_id, unit_id, initial_quantity, remaining_quantity,
			 expires_on, keep_confirmed_expires_on, created_at, updated_at)
			values (?, ?, '우유', ?, 1, 1, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""", id, userId, statusId, remainingQuantity, expiresOn, keepConfirmedExpiresOn);
	}

	private List<NotificationRow> rows() {
		return jdbc.query("""
			select id, inventory_item_id, sent_at from notifications where type = 'consume_check' order by id
			""", (rs, rowNum) -> new NotificationRow(
			rs.getLong("id"), rs.getLong("inventory_item_id"), rs.getObject("sent_at", LocalDateTime.class)));
	}

	private record NotificationRow(long id, long inventoryItemId, LocalDateTime sentAt) {
	}
}
