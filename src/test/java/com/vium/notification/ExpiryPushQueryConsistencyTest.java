package com.vium.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.vium.inventory.service.InventoryService;
import com.vium.notification.dto.ExpiryPushCandidate;
import com.vium.notification.repository.ExpiryPushQueryRepository;
import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

/**
 * ExpiryPushQueryRepository(배치용)가 기존 InventoryService.findExpiringIngredients
 * (NotificationService.expiringSoon이 쓰는 것과 동일)와 같은 기준으로 "임박"을 판단하는지 검증한다.
 * 유일한 의도된 차이는 "이미 지난 재고"(expires_on < 오늘) 제외뿐임을 명시적으로 테스트한다.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:expiry-push-consistency-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@Sql(scripts = "/db/inventory-query-support.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class ExpiryPushQueryConsistencyTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 11, 16);

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private InventoryService inventoryService;

	@Autowired
	private ExpiryPushQueryRepository expiryPushQueryRepository;

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
			values (1, null, 1, '우유', CURRENT_TIMESTAMP)
			""");

		// user 1: 알림일수 1, user 2: 2(기본), user 3: 14(최댓값)
		jdbc.update("""
			insert into "users" (id, email, display_name, expiry_alert_days, push_enabled, created_at, updated_at)
			values (1, 'u1@example.com', '사용자1', 1, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
			       (2, 'u2@example.com', '사용자2', 2, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
			       (3, 'u3@example.com', '사용자3', 14, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""");
		jdbc.update("""
			insert into "user_push_tokens" (user_id, token, platform, created_at, updated_at)
			values (1, 'token-1', 'ANDROID', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
			       (2, 'token-2', 'ANDROID', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
			       (3, 'token-3', 'ANDROID', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""");
	}

	private void insertItem(long id, long userId, int statusId, LocalDate expiresOn) {
		jdbc.update("""
			insert into "inventory_items"
			(id, user_id, ingredient_catalog_id, status_id, unit_id,
			 initial_quantity, remaining_quantity, purchased_on, expires_on, created_at, updated_at)
			values (?, ?, 1, ?, 1, 2.5, 1.25, DATE '2026-01-01', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""", id, userId, statusId, expiresOn);
	}

	private Set<Long> expectedSet(long userId, int alertDays) {
		return inventoryService.findExpiringIngredients(userId, TODAY.plusDays(alertDays)).stream()
			.map(item -> item.inventoryItemId())
			.collect(Collectors.toSet());
	}

	private Set<Long> actualSet(long userId) {
		return expiryPushQueryRepository.findCandidates(TODAY, TODAY.plusDays(14)).stream()
			.filter(c -> c.userId().equals(userId))
			.filter(c -> !c.expiresOn().isAfter(TODAY.plusDays(c.expiryAlertDays())))
			.map(ExpiryPushCandidate::inventoryItemId)
			.collect(Collectors.toSet());
	}

	@Test
	void matchesExpiringSoonExceptItExcludesAlreadyPastItems() {
		// user 1 (alertDays=1): 지남(배치만 제외), 오늘, 경계(+1), N+1(+2, 둘 다 제외), 소비기한없음(제외), 소진(제외), 폐기(제외)
		insertItem(101, 1, 1, TODAY.minusDays(1));
		insertItem(102, 1, 1, TODAY);
		insertItem(103, 1, 1, TODAY.plusDays(1));
		insertItem(104, 1, 1, TODAY.plusDays(2));
		insertItem(105, 1, 1, null);
		insertItem(106, 1, 2, TODAY);
		insertItem(107, 1, 3, TODAY);

		// user 2 (alertDays=2, 기본값)
		insertItem(201, 2, 1, TODAY.minusDays(1));
		insertItem(202, 2, 1, TODAY);
		insertItem(203, 2, 1, TODAY.plusDays(2));
		insertItem(204, 2, 1, TODAY.plusDays(3));
		insertItem(205, 2, 1, null);

		// user 3 (alertDays=14, 최댓값: 상한 바인딩과 동일한 경계)
		insertItem(301, 3, 1, TODAY.minusDays(1));
		insertItem(302, 3, 1, TODAY.plusDays(14));
		insertItem(303, 3, 1, TODAY.plusDays(15));
		insertItem(304, 3, 1, null);

		// 배치(actualSet)는 expiringSoon(expectedSet)의 부분집합이고, 차이는 "이미 지난 재고"뿐이다.
		assertThat(expectedSet(1, 1)).containsExactlyInAnyOrder(101L, 102L, 103L);
		assertThat(actualSet(1)).containsExactlyInAnyOrder(102L, 103L);
		assertThat(java.util.Set.of(101L)).isEqualTo(difference(expectedSet(1, 1), actualSet(1)));

		assertThat(expectedSet(2, 2)).containsExactlyInAnyOrder(201L, 202L, 203L);
		assertThat(actualSet(2)).containsExactlyInAnyOrder(202L, 203L);
		assertThat(java.util.Set.of(201L)).isEqualTo(difference(expectedSet(2, 2), actualSet(2)));

		assertThat(expectedSet(3, 14)).containsExactlyInAnyOrder(301L, 302L);
		assertThat(actualSet(3)).containsExactlyInAnyOrder(302L);
		assertThat(java.util.Set.of(301L)).isEqualTo(difference(expectedSet(3, 14), actualSet(3)));
	}

	private static Set<Long> difference(Set<Long> a, Set<Long> b) {
		Set<Long> result = new java.util.HashSet<>(a);
		result.removeAll(b);
		return result;
	}

	@Test
	void excludesUsersWithPushDisabledOrWithoutAnyToken() {
		jdbc.update("""
			insert into "users" (id, email, display_name, expiry_alert_days, push_enabled, created_at, updated_at)
			values (4, 'u4@example.com', '사용자4(푸시꺼짐)', 2, false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
			       (5, 'u5@example.com', '사용자5(토큰없음)', 2, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""");
		jdbc.update("""
			insert into "user_push_tokens" (user_id, token, platform, created_at, updated_at)
			values (4, 'token-4', 'ANDROID', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""");
		insertItem(401, 4, 1, TODAY); // push_enabled=false -> 제외
		insertItem(501, 5, 1, TODAY); // 토큰 없음 -> 제외

		var candidates = expiryPushQueryRepository.findCandidates(TODAY, TODAY.plusDays(14));

		assertThat(candidates).extracting(ExpiryPushCandidate::userId).doesNotContain(4L, 5L);
	}

	@Test
	void excludesItemAlreadySuccessfullyNotifiedButIncludesOneAwaitingRetry() {
		insertItem(601, 1, 1, TODAY); // 이미 성공 알림됨 -> 제외
		insertItem(602, 1, 1, TODAY); // 실패해서 sent_at null로 남음 -> 재시도 대상으로 포함
		jdbc.update("""
			insert into "notifications" (user_id, type, inventory_item_id, title, body, scheduled_at, sent_at, created_at)
			values (1, 'expiry', 601, '제목', '본문', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
			       (1, 'expiry', 602, '제목', '본문', CURRENT_TIMESTAMP, null, CURRENT_TIMESTAMP)
			""");

		var candidates = expiryPushQueryRepository.findCandidates(TODAY, TODAY.plusDays(14)).stream()
			.filter(c -> c.userId().equals(1L))
			.toList();

		assertThat(candidates).extracting(ExpiryPushCandidate::inventoryItemId).containsExactly(602L);
		assertThat(candidates.get(0).existingNotificationId()).isNotNull();
	}
}
