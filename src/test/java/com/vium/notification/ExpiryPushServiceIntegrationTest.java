package com.vium.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vium.notification.service.ExpiryPushService;
import com.vium.notification.service.FcmPushClient;
import com.vium.notification.service.PushResult;
import java.time.Clock;
import java.time.LocalDate;
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

/**
 * ExpiryPushService.sendDailyExpiryPush()의 하루 단위 반복 실행 동작(최초 1회만 알림,
 * 실패 시 재시도, 요약 개수, 하한 제외)을 실제 DB(H2) + Mockito FcmPushClient로 검증한다.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:expiry-push-service-it-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@Sql(scripts = "/db/inventory-query-support.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class ExpiryPushServiceIntegrationTest {

	private static final LocalDate DAY1 = LocalDate.of(2026, 11, 16);
	private static final LocalDate DAY2 = DAY1.plusDays(1);

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ExpiryPushService expiryPushService;

	@MockitoBean
	private Clock clock;

	@MockitoBean
	private FcmPushClient fcmPushClient;

	private void setToday(LocalDate day) {
		when(clock.instant()).thenReturn(day.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant().plusSeconds(32400)); // 09:00 KST
		when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
	}

	@BeforeEach
	void setUp() {
		jdbc.update("delete from \"notifications\"");
		jdbc.update("delete from \"consumption_events\"");
		jdbc.update("delete from \"inventory_items\"");
		jdbc.update("delete from \"user_push_tokens\"");
		jdbc.update("delete from \"ingredient_catalog\"");
		jdbc.update("delete from \"users\"");
		jdbc.update("delete from \"units\"");
		jdbc.update("delete from \"item_statuses\"");

		jdbc.update("insert into \"units\" (id, code, name) values (1, 'ea', '개')");
		jdbc.update("""
			insert into "item_statuses" (id, code, name)
			values (1, 'active', '보유중'), (2, 'consumed', '소진'), (3, 'disposed', '폐기')
			""");
		jdbc.update("""
			insert into "ingredient_catalog" (id, category_id, default_unit_id, name, created_at)
			values (1, null, 1, '우유', CURRENT_TIMESTAMP), (2, null, 1, '계란', CURRENT_TIMESTAMP)
			""");
		jdbc.update("""
			insert into "users" (id, email, display_name, expiry_alert_days, push_enabled, created_at, updated_at)
			values (1, 'u1@example.com', '사용자1', 2, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""");
		jdbc.update("""
			insert into "user_push_tokens" (user_id, token, platform, created_at, updated_at)
			values (1, 'token-1', 'ANDROID', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""");
		setToday(DAY1);
	}

	private void insertItem(long id, int catalogId, LocalDate expiresOn) {
		jdbc.update("""
			insert into "inventory_items"
			(id, user_id, ingredient_catalog_id, status_id, unit_id,
			 initial_quantity, remaining_quantity, purchased_on, expires_on, created_at, updated_at)
			values (?, 1, ?, 1, 1, 2.5, 1.25, DATE '2026-01-01', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""", id, catalogId, expiresOn);
	}

	private List<Map<String, Object>> notificationRows() {
		return jdbc.queryForList("select inventory_item_id, sent_at from \"notifications\" order by id");
	}

	@Test
	void sameItemTwoConsecutiveDaysOnlyPushesAndCreatesRowOnce() {
		insertItem(10, 1, DAY1.plusDays(1));
		when(fcmPushClient.send(any(), any(), any(), any())).thenReturn(PushResult.SUCCESS);

		int day1Sent = expiryPushService.sendDailyExpiryPush();
		assertThat(day1Sent).isEqualTo(1);
		assertThat(notificationRows()).hasSize(1);
		assertThat(notificationRows().get(0).get("sent_at")).isNotNull();

		setToday(DAY2);
		int day2Sent = expiryPushService.sendDailyExpiryPush();

		assertThat(day2Sent).isZero();
		assertThat(notificationRows()).hasSize(1);
		verify(fcmPushClient, times(1)).send(any(), any(), any(), any());
	}

	@Test
	void retriesSameRowNextDayAfterFirstDayAllTokensFailed() {
		insertItem(10, 1, DAY1.plusDays(1));
		when(fcmPushClient.send(any(), any(), any(), any())).thenReturn(PushResult.FAILED);

		int day1Sent = expiryPushService.sendDailyExpiryPush();
		assertThat(day1Sent).isZero();
		assertThat(notificationRows()).hasSize(1);
		assertThat(notificationRows().get(0).get("sent_at")).isNull();

		setToday(DAY2);
		when(fcmPushClient.send(any(), any(), any(), any())).thenReturn(PushResult.SUCCESS);
		int day2Sent = expiryPushService.sendDailyExpiryPush();

		assertThat(day2Sent).isEqualTo(1);
		assertThat(notificationRows()).hasSize(1); // 행 수 증가 없음, 같은 행 재사용
		assertThat(notificationRows().get(0).get("sent_at")).isNotNull();
	}

	@Test
	void summaryCountOnNextRunOnlyIncludesNewlyAddedItem() {
		insertItem(10, 1, DAY1.plusDays(1));
		insertItem(11, 2, DAY1.plusDays(1));
		when(fcmPushClient.send(any(), any(), any(), any())).thenReturn(PushResult.SUCCESS);
		expiryPushService.sendDailyExpiryPush(); // 둘 다 최초 알림: 요약 "2개"

		verify(fcmPushClient).send(any(), any(), eq("소비기한이 임박한 재료가 2개 있어요"), any());
		reset(fcmPushClient);
		when(fcmPushClient.send(any(), any(), any(), any())).thenReturn(PushResult.SUCCESS);

		insertItem(12, 1, DAY1.plusDays(1)); // 다음 날 추가된 새 재고 1건
		setToday(DAY2);
		int day2Sent = expiryPushService.sendDailyExpiryPush();

		assertThat(day2Sent).isEqualTo(1);
		verify(fcmPushClient).send(any(), any(), eq("소비기한이 임박한 재료가 1개 있어요"), any());
		assertThat(notificationRows()).hasSize(3);
	}

	@Test
	void sendsNoPushWhenThereAreNoCandidates() {
		int sentUserCount = expiryPushService.sendDailyExpiryPush();

		assertThat(sentUserCount).isZero();
		assertThat(notificationRows()).isEmpty();
		verifyNoInteractions(fcmPushClient);
	}
}
