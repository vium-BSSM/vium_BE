package com.vium.dispose;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vium.dispose.entity.ConsumptionEvent;
import com.vium.dispose.repository.ConsumptionEventRepository;
import com.vium.dispose.service.AutoDisposeService;
import com.vium.inventory.service.InventoryService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.ScheduledTaskHolder;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:auto-dispose-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class AutoDisposeIntegrationTest {

	@Autowired
	private AutoDisposeService autoDisposeService;

	@Autowired
	private InventoryService inventoryService;

	@Autowired
	private ConsumptionEventRepository consumptionEventRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private Environment environment;

	@Autowired(required = false)
	private ScheduledTaskHolder scheduledTaskHolder;

	@Autowired
	private ApplicationContext applicationContext;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("delete from \"consumption_events\"");
		jdbcTemplate.update("delete from \"inventory_items\"");
		jdbcTemplate.update("delete from \"item_statuses\"");
		jdbcTemplate.update("delete from \"event_sources\"");

		jdbcTemplate.update("insert into \"item_statuses\" (\"id\", \"code\", \"name\") values (1, 'active', '보유'), (2, 'consumed', '소진'), (3, 'disposed', '폐기')");
		jdbcTemplate.update("insert into \"event_sources\" (\"id\", \"code\", \"name\") values (1, 'manual', '수동'), (2, 'auto_estimated', '자동추정')");
	}

	@Test
	void disposesExpiredActiveItemWithProportionalAmountAndNextDayOccurredAt() {
		LocalDate expiresOn = LocalDate.now().minusDays(3);
		jdbcTemplate.update("""
			insert into "inventory_items"
			("id", "user_id", "custom_name", "status_id", "unit_id", "initial_quantity", "remaining_quantity",
			 "amount", "expires_on", "created_at", "updated_at")
			values (300, 42, '상추', 1, 1, 10, 4, 2000, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""", expiresOn);

		int processedCount = autoDisposeService.disposeExpiredItems();

		assertThat(processedCount).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject(
			"select status_id from inventory_items where id = 300", Integer.class)).isEqualTo(3);
		assertThat(jdbcTemplate.queryForObject(
			"select remaining_quantity from inventory_items where id = 300", BigDecimal.class))
			.isEqualByComparingTo(BigDecimal.ZERO);

		List<ConsumptionEvent> events = consumptionEventRepository.findAll();
		assertThat(events).singleElement().satisfies(event -> {
			assertThat(event.getInventoryItemId()).isEqualTo(300L);
			assertThat(event.getEventType()).isEqualTo("disposed");
			assertThat(event.getQuantity()).isEqualByComparingTo("4");
			// 비례 계산: 2000 * 4 / 10 = 800
			assertThat(event.getAmount()).isEqualByComparingTo("800.00");
			// occurredAt = expires_on 다음 날 00:00
			assertThat(event.getOccurredAt()).isEqualTo(expiresOn.plusDays(1).atStartOfDay());
		});
	}

	@Test
	void excludesItemsNotYetExpired() {
		LocalDate expiresToday = LocalDate.now();
		jdbcTemplate.update("""
			insert into "inventory_items"
			("id", "user_id", "custom_name", "status_id", "unit_id", "initial_quantity", "remaining_quantity",
			 "expires_on", "created_at", "updated_at")
			values (301, 42, '오이', 1, 1, 5, 5, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""", expiresToday);

		int processedCount = autoDisposeService.disposeExpiredItems();

		assertThat(processedCount).isZero();
		assertThat(jdbcTemplate.queryForObject(
			"select status_id from inventory_items where id = 301", Integer.class)).isEqualTo(1);
		assertThat(consumptionEventRepository.count()).isZero();
	}

	@Test
	void excludesItemsWithZeroRemainingQuantity() {
		LocalDate expiresOn = LocalDate.now().minusDays(1);
		jdbcTemplate.update("""
			insert into "inventory_items"
			("id", "user_id", "custom_name", "status_id", "unit_id", "initial_quantity", "remaining_quantity",
			 "expires_on", "created_at", "updated_at")
			values (302, 42, '파프리카', 1, 1, 5, 0, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""", expiresOn);

		int processedCount = autoDisposeService.disposeExpiredItems();

		assertThat(processedCount).isZero();
		assertThat(consumptionEventRepository.count()).isZero();
	}

	@Test
	void excludesAlreadyNonActiveItems() {
		LocalDate expiresOn = LocalDate.now().minusDays(1);
		jdbcTemplate.update("""
			insert into "inventory_items"
			("id", "user_id", "custom_name", "status_id", "unit_id", "initial_quantity", "remaining_quantity",
			 "expires_on", "created_at", "updated_at")
			values (303, 42, '버섯', 2, 1, 5, 5, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""", expiresOn);

		int processedCount = autoDisposeService.disposeExpiredItems();

		assertThat(processedCount).isZero();
		assertThat(jdbcTemplate.queryForObject(
			"select status_id from inventory_items where id = 303", Integer.class)).isEqualTo(2);
		assertThat(consumptionEventRepository.count()).isZero();
	}

	@Test
	void skipsDisposalWhenRemainingQuantityChangedConcurrently() {
		// 실제 DB에는 이미 다른 프로세스가 폐기 처리해 remaining_quantity가 0으로 바뀐 상태를 시뮬레이션
		LocalDate expiresOn = LocalDate.now().minusDays(1);
		jdbcTemplate.update("""
			insert into "inventory_items"
			("id", "user_id", "custom_name", "status_id", "unit_id", "initial_quantity", "remaining_quantity",
			 "expires_on", "created_at", "updated_at")
			values (304, 42, '깻잎', 3, 1, 5, 0, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""", expiresOn);

		// 조건부 UPDATE 호출: 읽은 값(5)과 실제 DB 값(0)이 다르므로 실패해야 한다
		boolean updated = inventoryService.disposeIfUnchanged(
			304L, (short) 1, (short) 3, BigDecimal.valueOf(5), java.time.LocalDateTime.now());

		assertThat(updated).isFalse();
		// 상태는 테스트에서 직접 넣은 값(3=disposed)을 그대로 유지, remaining_quantity도 변화 없음
		assertThat(jdbcTemplate.queryForObject(
			"select status_id from inventory_items where id = 304", Integer.class)).isEqualTo(3);
		assertThat(jdbcTemplate.queryForObject(
			"select remaining_quantity from inventory_items where id = 304", BigDecimal.class))
			.isEqualByComparingTo(BigDecimal.ZERO);
	}

	@Test
	void updatesUpdatedAtColumnOnConditionalDispose() {
		LocalDate expiresOn = LocalDate.now().minusDays(1);
		jdbcTemplate.update("""
			insert into "inventory_items"
			("id", "user_id", "custom_name", "status_id", "unit_id", "initial_quantity", "remaining_quantity",
			 "expires_on", "created_at", "updated_at")
			values (305, 42, '무', 1, 1, 3, 3, ?, CURRENT_TIMESTAMP, TIMESTAMP '2020-01-01 00:00:00')
			""", expiresOn);

		java.time.LocalDateTime explicitNow = java.time.LocalDateTime.of(2026, 10, 13, 9, 0, 0);
		boolean updated = inventoryService.disposeIfUnchanged(
			305L, (short) 1, (short) 3, BigDecimal.valueOf(3), explicitNow);

		assertThat(updated).isTrue();
		assertThat(jdbcTemplate.queryForObject(
			"select updated_at from inventory_items where id = 305", java.time.LocalDateTime.class))
			.isEqualTo(explicitNow);
	}

	@Test
	void autoDisposeEnabledPropertyDefaultsToFalse() {
		// application.yml 기본값: disposal.auto-dispose.enabled=false
		assertThat(environment.getProperty("disposal.auto-dispose.enabled", Boolean.class, false)).isFalse();
	}

	@Test
	void scheduledCronTaskIsNotRegisteredWhenDisabledByDefault() {
		// disposal.auto-dispose.enabled=false(기본값)이면 AutoDisposeScheduler 빈 자체가
		// 등록되지 않으므로 그 안의 @Scheduled 작업도 Spring에 등록되지 않아야 한다.
		assertThat(scheduledTaskHolder).isNotNull();
		boolean cronTaskRegistered = scheduledTaskHolder.getScheduledTasks().stream()
			.anyMatch(task -> task.getTask().toString().contains("AutoDisposeScheduler"));
		assertThat(cronTaskRegistered).isFalse();
	}

	@Test
	void autoDisposeSchedulerBeanDoesNotExistWhenDisabled() {
		assertThatThrownBy(() -> applicationContext.getBean(
			com.vium.dispose.service.AutoDisposeScheduler.class))
			.isInstanceOf(org.springframework.beans.factory.NoSuchBeanDefinitionException.class);
	}
}
