package com.vium.dispose;

import com.vium.auth.service.TokenService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.dispose.entity.ConsumptionEvent;
import com.vium.dispose.repository.ConsumptionEventRepository;
import com.vium.dispose.service.DisposeService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:consumption-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class DisposeIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private DisposeService disposeService;

	@MockitoSpyBean
	private ConsumptionEventRepository consumptionEventRepository;

	@Autowired
	private TokenService tokenService;

	@BeforeEach
	void setUp() {
		// ingredient_categories는 JPA 엔티티가 없어 Hibernate ddl-auto로 생성되지 않는다.
		// waste-patterns 쿼리가 이 테이블을 조인하므로 이 테스트 클래스에서 직접 만든다.
		jdbcTemplate.update("""
			create table if not exists "ingredient_categories"
			("id" smallint primary key, "name" varchar(60) unique not null, "parent_id" smallint)
			""");

		jdbcTemplate.update("delete from \"consumption_events\"");
		jdbcTemplate.update("delete from \"inventory_items\"");
		jdbcTemplate.update("delete from \"item_statuses\"");
		jdbcTemplate.update("delete from \"event_sources\"");
		jdbcTemplate.update("delete from \"ingredient_categories\"");

		jdbcTemplate.update("insert into \"item_statuses\" (\"id\", \"code\", \"name\") values (1, 'active', '보유'), (2, 'consumed', '소진'), (3, 'disposed', '폐기')");
		jdbcTemplate.update("insert into \"event_sources\" (\"id\", \"code\", \"name\") values (1, 'manual', '수동'), (2, 'auto_estimated', '자동추정')");
		jdbcTemplate.update("""
			insert into "inventory_items"
			("id", "user_id", "custom_name", "status_id", "unit_id", "initial_quantity", "remaining_quantity", "created_at", "updated_at")
			values (100, 42, '두부', 1, 1, 5, 5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""");
	}

	@Test
	void consumesInventoryAndRecordsEventForProvidedUser() throws Exception {
		mockMvc.perform(patch("/api/me/ingredients/100/status").header("Authorization", "Bearer " + tokenService.issue(42L).accessToken())
				.contentType(MediaType.APPLICATION_JSON).content("""
				{"status":"consumed","quantity":2}
				"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.inventoryItemId").value(100))
			.andExpect(jsonPath("$.data.statusCode").value("consumed"))
			.andExpect(jsonPath("$.data.remainingQuantity").value(3));
		assertInventory(3, 2);
		assertThat(consumptionEventRepository.findAll()).singleElement().satisfies(event -> {
			assertThat(event.getUserId()).isEqualTo(42L);
			assertThat(event.getInventoryItemId()).isEqualTo(100L);
			assertThat(event.getQuantity()).isEqualByComparingTo("2");
			assertThat(event.getEventType()).isEqualTo("consumed");
		});
	}

	@Test
	void disposesInventoryAndPreservesWasteResponseAndAmount() throws Exception {
		mockMvc.perform(patch("/api/me/ingredients/100/status").header("Authorization", "Bearer " + tokenService.issue(42L).accessToken())
				.contentType(MediaType.APPLICATION_JSON).content("""
				{"status":"disposed","quantity":2,"wasteQuantity":2,"wasteAmount":1000}
				"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.wasteQuantity").value(2))
			.andExpect(jsonPath("$.data.wasteAmount").value(1000));
		assertInventory(3, 3);
		assertThat(consumptionEventRepository.findAll()).singleElement().satisfies(event -> {
			assertThat(event.getAmount()).isEqualByComparingTo("1000");
			assertThat(event.getEventType()).isEqualTo("disposed");
		});
	}

	@Test
	void rejectsAnotherUsersInventory() throws Exception {
		mockMvc.perform(patch("/api/me/ingredients/100/status").header("Authorization", "Bearer " + tokenService.issue(7L).accessToken())
				.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"consumed\",\"quantity\":2}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
		assertInventory(5, 1);
		assertThat(consumptionEventRepository.count()).isZero();
	}

	@Test
	void rejectsInvalidQuantityBeforeChangingInventory() throws Exception {
		mockMvc.perform(patch("/api/me/ingredients/100/status").header("Authorization", "Bearer " + tokenService.issue(42L).accessToken())
				.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"consumed\",\"quantity\":0}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
		assertInventory(5, 1);
		assertThat(consumptionEventRepository.count()).isZero();
	}

	@Test
	void rollsBackInventoryWhenEventPersistenceFails() throws Exception {
		doThrow(new DataIntegrityViolationException("event write failed"))
			.when(consumptionEventRepository).save(any(ConsumptionEvent.class));
		mockMvc.perform(patch("/api/me/ingredients/100/status").header("Authorization", "Bearer " + tokenService.issue(42L).accessToken())
				.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"consumed\",\"quantity\":2}"))
			.andExpect(status().isInternalServerError());
		// No test transaction: this reads the database after the request transaction has ended.
		assertInventory(5, 1);
		assertThat(consumptionEventRepository.count()).isZero();
	}

	@Test
	void rollsBackBothEventsWhenSecondEventPersistenceFailsDuringPartialDisposal() throws Exception {
		jdbcTemplate.update("""
			insert into "inventory_items"
			("id", "user_id", "custom_name", "status_id", "unit_id", "initial_quantity", "remaining_quantity", "amount", "created_at", "updated_at")
			values (101, 42, '양파', 1, 1, 5, 5, 1000, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""");
		doThrow(new DataIntegrityViolationException("second event write failed"))
			.when(consumptionEventRepository).save(any(ConsumptionEvent.class));
		mockMvc.perform(patch("/api/me/ingredients/101/status").header("Authorization", "Bearer " + tokenService.issue(42L).accessToken())
				.contentType(MediaType.APPLICATION_JSON).content("""
				{"status":"disposed","quantity":5,"wasteQuantity":2}
				"""))
			.andExpect(status().isInternalServerError());
		assertThat(jdbcTemplate.queryForObject(
			"select remaining_quantity from inventory_items where id = 101", BigDecimal.class))
			.isEqualByComparingTo(BigDecimal.valueOf(5));
		assertThat(jdbcTemplate.queryForObject(
			"select status_id from inventory_items where id = 101", Integer.class))
			.isEqualTo(1);
		assertThat(consumptionEventRepository.count()).isZero();
	}

	// ===== A 시나리오: 전량/부분 처리, 비례 amount, 검증 =====

	@Test
	void fullyConsumesEntireRemainingQuantity() throws Exception {
		mockMvc.perform(patch("/api/me/ingredients/100/status").header("Authorization", "Bearer " + tokenService.issue(42L).accessToken())
				.contentType(MediaType.APPLICATION_JSON).content("""
				{"status":"consumed","quantity":5}
				"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.statusCode").value("consumed"))
			.andExpect(jsonPath("$.data.remainingQuantity").value(0))
			.andExpect(jsonPath("$.data.wasteQuantity").doesNotExist())
			.andExpect(jsonPath("$.data.wasteAmount").doesNotExist());
		assertInventory(0, 2);
		assertThat(consumptionEventRepository.findAll()).singleElement().satisfies(event -> {
			assertThat(event.getEventType()).isEqualTo("consumed");
			assertThat(event.getQuantity()).isEqualByComparingTo("5");
		});
	}

	@Test
	void fullyDisposesWithDefaultWasteQuantity() throws Exception {
		// wasteQuantity 생략 시 quantity 전체가 폐기량이 된다
		mockMvc.perform(patch("/api/me/ingredients/100/status").header("Authorization", "Bearer " + tokenService.issue(42L).accessToken())
				.contentType(MediaType.APPLICATION_JSON).content("""
				{"status":"disposed","quantity":5}
				"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.statusCode").value("disposed"))
			.andExpect(jsonPath("$.data.remainingQuantity").value(0))
			.andExpect(jsonPath("$.data.wasteQuantity").value(5));
		assertInventory(0, 3);
		assertThat(consumptionEventRepository.findAll()).singleElement().satisfies(event -> {
			assertThat(event.getEventType()).isEqualTo("disposed");
			assertThat(event.getQuantity()).isEqualByComparingTo("5");
		});
	}

	@Test
	void partiallyDisposesAndRecordsTwoEventsWithProportionalAmounts() throws Exception {
		jdbcTemplate.update("""
			insert into "inventory_items"
			("id", "user_id", "custom_name", "status_id", "unit_id", "initial_quantity", "remaining_quantity", "amount", "created_at", "updated_at")
			values (102, 42, '감자', 1, 1, 5, 5, 1000, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""");

		// quantity=5(남은 수량 전체), wasteQuantity=2(일부만 폐기) → disposed(2) + consumed(3) 이벤트 2건
		// 정리 플로우 계약: quantity(5) 전체가 재고에서 차감되어야 한다(remainingQuantity=0).
		mockMvc.perform(patch("/api/me/ingredients/102/status").header("Authorization", "Bearer " + tokenService.issue(42L).accessToken())
				.contentType(MediaType.APPLICATION_JSON).content("""
				{"status":"disposed","quantity":5,"wasteQuantity":2}
				"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.statusCode").value("disposed"))
			.andExpect(jsonPath("$.data.remainingQuantity").value(0))
			.andExpect(jsonPath("$.data.wasteQuantity").value(2))
			// amount 명시 안 함 → 비례 계산: 1000 * 2 / 5 = 400
			.andExpect(jsonPath("$.data.wasteAmount").value(400));

		// DB의 inventory_items도 전량 처리 결과(remaining=0, status=disposed)와 일치해야 한다.
		assertThat(jdbcTemplate.queryForObject(
			"select remaining_quantity from inventory_items where id = 102", BigDecimal.class))
			.isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(jdbcTemplate.queryForObject(
			"select status_id from inventory_items where id = 102", Integer.class))
			.isEqualTo(3);

		List<ConsumptionEvent> events = consumptionEventRepository.findAll();
		assertThat(events).hasSize(2);

		ConsumptionEvent disposedEvent = events.stream()
			.filter(e -> e.getEventType().equals("disposed")).findFirst().orElseThrow();
		assertThat(disposedEvent.getQuantity()).isEqualByComparingTo("2");
		assertThat(disposedEvent.getAmount()).isEqualByComparingTo("400.00");

		ConsumptionEvent consumedEvent = events.stream()
			.filter(e -> e.getEventType().equals("consumed")).findFirst().orElseThrow();
		assertThat(consumedEvent.getQuantity()).isEqualByComparingTo("3");
		// 비례 계산: 1000 * 3 / 5 = 600
		assertThat(consumedEvent.getAmount()).isEqualByComparingTo("600.00");

		// 두 이벤트의 occurredAt이 동일해야 한다 (같은 Clock 호출 결과를 공유)
		assertThat(disposedEvent.getOccurredAt()).isEqualTo(consumedEvent.getOccurredAt());
	}

	@Test
	void partiallyDisposesWhenInventoryAlreadyPartiallyConsumedBeforehand() throws Exception {
		// 로컬 PostgreSQL 검증에서 재현된 시나리오:
		// 1) consumed 3개 처리 (초기 10 → 남은 7)
		// 2) 남은 7 전체를 disposed 처리하되 wasteQuantity=2(일부만 폐기, 나머지 5는 consumed로 자동 기록)
		// 정리 플로우 계약: 두 번째 요청의 quantity(7) 전체가 재고에서 차감되어야 한다(remainingQuantity=0).
		jdbcTemplate.update("""
			insert into "inventory_items"
			("id", "user_id", "custom_name", "status_id", "unit_id", "initial_quantity", "remaining_quantity", "amount", "created_at", "updated_at")
			values (104, 42, '우유', 1, 1, 10, 10, 5000, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""");

		mockMvc.perform(patch("/api/me/ingredients/104/status").header("Authorization", "Bearer " + tokenService.issue(42L).accessToken())
				.contentType(MediaType.APPLICATION_JSON).content("""
				{"status":"consumed","quantity":3}
				"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.remainingQuantity").value(7));

		mockMvc.perform(patch("/api/me/ingredients/104/status").header("Authorization", "Bearer " + tokenService.issue(42L).accessToken())
				.contentType(MediaType.APPLICATION_JSON).content("""
				{"status":"disposed","quantity":7,"wasteQuantity":2}
				"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.statusCode").value("disposed"))
			.andExpect(jsonPath("$.data.remainingQuantity").value(0))
			.andExpect(jsonPath("$.data.wasteQuantity").value(2))
			// 1000 * ... 아님, amount=5000, initialQuantity=10 → 5000*2/10=1000
			.andExpect(jsonPath("$.data.wasteAmount").value(1000));

		assertThat(jdbcTemplate.queryForObject(
			"select remaining_quantity from inventory_items where id = 104", BigDecimal.class))
			.isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(jdbcTemplate.queryForObject(
			"select status_id from inventory_items where id = 104", Integer.class))
			.isEqualTo(3);

		List<ConsumptionEvent> events = consumptionEventRepository.findAll();
		assertThat(events).hasSize(3);
		assertThat(events.stream().map(ConsumptionEvent::getQuantity).reduce(BigDecimal.ZERO, BigDecimal::add))
			.isEqualByComparingTo("10");
	}

	@Test
	void partiallyDisposesWithDecimalWasteQuantity() throws Exception {
		jdbcTemplate.update("""
			insert into "inventory_items"
			("id", "user_id", "custom_name", "status_id", "unit_id", "initial_quantity", "remaining_quantity", "amount", "created_at", "updated_at")
			values (103, 42, '당근', 1, 1, 5, 5, 1000, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""");

		// 프론트 실제 요청 형태: quantity=남은 수량 전체(5), wasteQuantity=일부(3.3)
		mockMvc.perform(patch("/api/me/ingredients/103/status").header("Authorization", "Bearer " + tokenService.issue(42L).accessToken())
				.contentType(MediaType.APPLICATION_JSON).content("""
				{"status":"disposed","quantity":5,"wasteQuantity":3.3}
				"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.remainingQuantity").value(0))
			.andExpect(jsonPath("$.data.wasteQuantity").value(3.3))
			// 1000 * 3.3 / 5 = 660.00 → 응답은 정수 반올림 660
			.andExpect(jsonPath("$.data.wasteAmount").value(660));

		assertThat(jdbcTemplate.queryForObject(
			"select remaining_quantity from inventory_items where id = 103", BigDecimal.class))
			.isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(jdbcTemplate.queryForObject(
			"select status_id from inventory_items where id = 103", Integer.class))
			.isEqualTo(3);

		List<ConsumptionEvent> events = consumptionEventRepository.findAll();
		assertThat(events).hasSize(2);
		ConsumptionEvent disposedEvent = events.stream()
			.filter(e -> e.getEventType().equals("disposed")).findFirst().orElseThrow();
		assertThat(disposedEvent.getQuantity()).isEqualByComparingTo("3.3");
		assertThat(disposedEvent.getAmount()).isEqualByComparingTo("660.00");

		ConsumptionEvent consumedEvent = events.stream()
			.filter(e -> e.getEventType().equals("consumed")).findFirst().orElseThrow();
		assertThat(consumedEvent.getQuantity()).isEqualByComparingTo("1.7");
		// 1000 * 1.7 / 5 = 340.00
		assertThat(consumedEvent.getAmount()).isEqualByComparingTo("340.00");
	}

	@Test
	void rejectsWasteQuantityGreaterThanQuantity() throws Exception {
		mockMvc.perform(patch("/api/me/ingredients/100/status").header("Authorization", "Bearer " + tokenService.issue(42L).accessToken())
				.contentType(MediaType.APPLICATION_JSON).content("""
				{"status":"disposed","quantity":2,"wasteQuantity":3}
				"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
		assertInventory(5, 1);
		assertThat(consumptionEventRepository.count()).isZero();
	}

	@Test
	void rejectsQuantityWithFourDecimalPlaces() throws Exception {
		mockMvc.perform(patch("/api/me/ingredients/100/status").header("Authorization", "Bearer " + tokenService.issue(42L).accessToken())
				.contentType(MediaType.APPLICATION_JSON).content("""
				{"status":"consumed","quantity":1.2345}
				"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
		assertInventory(5, 1);
		assertThat(consumptionEventRepository.count()).isZero();
	}

	@Test
	void rejectsQuantityExceedingRemainingQuantity() throws Exception {
		mockMvc.perform(patch("/api/me/ingredients/100/status").header("Authorization", "Bearer " + tokenService.issue(42L).accessToken())
				.contentType(MediaType.APPLICATION_JSON).content("""
				{"status":"consumed","quantity":6}
				"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
		assertInventory(5, 1);
		assertThat(consumptionEventRepository.count()).isZero();
	}

	@Test
	void recordsNullAmountWhenItemAmountIsNull() throws Exception {
		// item 100은 amount가 null (setUp에서 미설정)
		mockMvc.perform(patch("/api/me/ingredients/100/status").header("Authorization", "Bearer " + tokenService.issue(42L).accessToken())
				.contentType(MediaType.APPLICATION_JSON).content("""
				{"status":"disposed","quantity":5,"wasteQuantity":2}
				"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.wasteAmount").doesNotExist());

		List<ConsumptionEvent> events = consumptionEventRepository.findAll();
		assertThat(events).hasSize(2);
		assertThat(events).allSatisfy(event -> assertThat(event.getAmount()).isNull());
	}

	// ===== 프론트 호환성: 실제 요청 형태 =====

	@Test
	void frontendCompat_consumedWithFullRemainingQuantity() throws Exception {
		mockMvc.perform(patch("/api/me/ingredients/100/status").header("Authorization", "Bearer " + tokenService.issue(42L).accessToken())
				.contentType(MediaType.APPLICATION_JSON).content("""
				{"status":"consumed","quantity":5}
				"""))
			.andExpect(status().isOk());
		assertThat(consumptionEventRepository.count()).isEqualTo(1);
	}

	@Test
	void frontendCompat_disposedWithFullWasteQuantity() throws Exception {
		mockMvc.perform(patch("/api/me/ingredients/100/status").header("Authorization", "Bearer " + tokenService.issue(42L).accessToken())
				.contentType(MediaType.APPLICATION_JSON).content("""
				{"status":"disposed","quantity":5,"wasteQuantity":5}
				"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.statusCode").value("disposed"));
		assertThat(consumptionEventRepository.count()).isEqualTo(1);
		assertThat(consumptionEventRepository.findAll()).singleElement()
			.satisfies(event -> assertThat(event.getEventType()).isEqualTo("disposed"));
	}

	@Test
	void frontendCompat_disposedWithPartialWasteQuantity() throws Exception {
		mockMvc.perform(patch("/api/me/ingredients/100/status").header("Authorization", "Bearer " + tokenService.issue(42L).accessToken())
				.contentType(MediaType.APPLICATION_JSON).content("""
				{"status":"disposed","quantity":5,"wasteQuantity":3.3}
				"""))
			.andExpect(status().isOk());
		assertThat(consumptionEventRepository.count()).isEqualTo(2);
	}

	@Test
	void frontendCompat_disposedWithZeroWasteQuantityIsTreatedAsConsumed() throws Exception {
		// wasteQuantity=0은 400 거부가 아니라 consumed로 처리되는 유일한 예외 케이스
		mockMvc.perform(patch("/api/me/ingredients/100/status").header("Authorization", "Bearer " + tokenService.issue(42L).accessToken())
				.contentType(MediaType.APPLICATION_JSON).content("""
				{"status":"disposed","quantity":5,"wasteQuantity":0}
				"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.statusCode").value("consumed"))
			.andExpect(jsonPath("$.data.remainingQuantity").value(0))
			.andExpect(jsonPath("$.data.wasteQuantity").doesNotExist())
			.andExpect(jsonPath("$.data.wasteAmount").doesNotExist());
		assertInventory(0, 2);
		assertThat(consumptionEventRepository.findAll()).singleElement().satisfies(event -> {
			assertThat(event.getEventType()).isEqualTo("consumed");
			assertThat(event.getQuantity()).isEqualByComparingTo("5");
		});
	}

	@Test
	void eventQueryPreservesUserDateTypeFiltersAndDescendingOrder() {
		jdbcTemplate.update("""
			insert into "consumption_events"
			("id", "inventory_item_id", "user_id", "event_type", "event_source_id", "quantity", "occurred_at", "created_at")
			values
			(1,100,42,'consumed',1,1,TIMESTAMP '2026-09-10 00:00:00',CURRENT_TIMESTAMP),
			(2,100,42,'consumed',1,1,TIMESTAMP '2026-09-10 23:59:59',CURRENT_TIMESTAMP),
			(3,100,42,'disposed',2,1,TIMESTAMP '2026-09-10 12:00:00',CURRENT_TIMESTAMP),
			(4,100,7,'consumed',1,1,TIMESTAMP '2026-09-10 12:00:00',CURRENT_TIMESTAMP),
			(5,100,42,'consumed',1,1,TIMESTAMP '2026-09-11 00:00:00',CURRENT_TIMESTAMP)
			""");
		LocalDate day = LocalDate.of(2026, 9, 10);
		assertThat(disposeService.getEvents(42L, day, day, "consumed").events())
			.extracting(event -> event.eventId()).containsExactly(2L, 1L);
		assertThat(disposeService.getEvents(42L, null, null, null).events()).hasSize(4);
		assertThat(disposeService.getEvents(99L, null, null, null).events()).isEmpty();
	}

	@Test
	void autoDisposeOnlyIncludesActiveItems() {
		Long userId = 42L;
		LocalDate yesterday = LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).minusDays(1);

		jdbcTemplate.update("""
			update "inventory_items"
			set "expires_on" = ? where "id" = 100
			""", yesterday);

		jdbcTemplate.update("""
			insert into "inventory_items"
			("id", "user_id", "custom_name", "status_id", "unit_id", "initial_quantity", "remaining_quantity", "expires_on", "created_at", "updated_at")
			values (200, ?, '망고', 2, 1, 3, 3, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""", userId, yesterday);

		assertThat(jdbcTemplate.queryForObject("""
			select count(*) from "inventory_items" where "user_id" = ? and "status_id" = 1 and "expires_on" < ?
			""", Integer.class, userId, yesterday.plusDays(1)))
			.isEqualTo(1);

		assertThat(jdbcTemplate.queryForObject("""
			select count(*) from "inventory_items" where "user_id" = ? and "status_id" = 2 and "expires_on" < ?
			""", Integer.class, userId, yesterday.plusDays(1)))
			.isEqualTo(1);
	}


	@Test
	void getWastePatternsEndpoint_Empty() throws Exception {
		mockMvc.perform(get("/api/me/waste-patterns")
				.header("Authorization", "Bearer " + tokenService.issue(42L).accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.patterns").isArray())
			.andExpect(jsonPath("$.data.patterns.length()").value(0));
	}

	@Test
	void getWastePatternsEndpoint_Unauthorized() throws Exception {
		mockMvc.perform(get("/api/me/waste-patterns"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.success").value(false));
	}

	@Test
	void getWasteReportsEndpoint_Unauthorized() throws Exception {
		// 인증 레이어에서 걸러지므로 WasteReportQueryRepository의 SQL까지 도달하지 않아 H2에서도 검증 가능하다.
		mockMvc.perform(get("/api/me/waste-reports"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.success").value(false));
	}

	// 주의: GET /api/me/waste-reports의 happy-path(빈 배열, wasteReportId 정수, 사용자 격리)는
	// WasteReportQueryRepository.findReports()가 PostgreSQL 전용 구문(interval '1 month')을 사용해
	// H2(MODE=PostgreSQL)에서 "Cannot parse INTERVAL constant" 오류로 실패한다.
	// 데이터 유무와 무관하게 SQL 파싱 단계에서 실패하므로 빈 배열 케이스도 H2로 검증할 수 없다.
	// PostgreSQL 환경에서 별도로 검증이 필요하다 (최종 보고서 참고).

	@Test
	void getWastePatternsEndpoint_RatioSortedDescendingWithZeroDenominatorExcluded() throws Exception {
		jdbcTemplate.update("""
			insert into "ingredient_categories" ("id", "name") values (1, '유제품'), (2, '채소')
			""");
		jdbcTemplate.update("""
			insert into "ingredient_catalog" ("id", "name", "category_id", "default_unit_id", "created_at")
			values (1, '우유', 1, 1, CURRENT_TIMESTAMP), (2, '당근', 2, 1, CURRENT_TIMESTAMP)
			""");
		jdbcTemplate.update("""
			insert into "inventory_items"
			("id", "user_id", "ingredient_catalog_id", "status_id", "unit_id", "initial_quantity", "remaining_quantity", "created_at", "updated_at")
			values
			(201, 42, 1, 3, 1, 1, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
			(202, 42, 2, 3, 1, 1, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""");
		jdbcTemplate.update("""
			insert into "consumption_events"
			("inventory_item_id", "user_id", "event_type", "event_source_id", "quantity", "amount", "occurred_at", "created_at")
			values
			(201, 42, 'disposed', 1, 1, 7000, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
			(202, 42, 'disposed', 1, 1, 3000, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""");

		mockMvc.perform(get("/api/me/waste-patterns")
				.header("Authorization", "Bearer " + tokenService.issue(42L).accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.patterns.length()").value(2))
			.andExpect(jsonPath("$.data.patterns[0].categoryName").value("유제품"))
			.andExpect(jsonPath("$.data.patterns[0].wasteRatio").value(0.7))
			.andExpect(jsonPath("$.data.patterns[1].categoryName").value("채소"))
			.andExpect(jsonPath("$.data.patterns[1].wasteRatio").value(0.3));
	}

	@Test
	void getConsumptionEventsEndpoint_Empty() throws Exception {
		mockMvc.perform(get("/api/me/consumption-events")
				.header("Authorization", "Bearer " + tokenService.issue(42L).accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.events").isArray())
			.andExpect(jsonPath("$.data.events.length()").value(0));
	}

	@Test
	void getConsumptionEventsEndpoint_WithData() throws Exception {
		LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
		jdbcTemplate.update("""
			insert into "consumption_events"
			("id", "inventory_item_id", "user_id", "event_type", "event_source_id", "quantity", "amount", "occurred_at", "created_at")
			values (1, 100, 42, 'consumed', 1, 2, null, ?, CURRENT_TIMESTAMP)
			""", today.atStartOfDay());

		mockMvc.perform(get("/api/me/consumption-events")
				.header("Authorization", "Bearer " + tokenService.issue(42L).accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.events.length()").value(1))
			.andExpect(jsonPath("$.data.events[0].eventId").value(1))
			.andExpect(jsonPath("$.data.events[0].eventType").value("consumed"))
			.andExpect(jsonPath("$.data.events[0].eventSource").value("manual"))
			.andExpect(jsonPath("$.data.events[0].occurredAt").exists());
	}

	@Test
	void getConsumptionEventsEndpoint_InvalidEventType() throws Exception {
		mockMvc.perform(get("/api/me/consumption-events?eventType=invalid")
				.header("Authorization", "Bearer " + tokenService.issue(42L).accessToken()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.message").value("eventType must be 'consumed' or 'disposed'"));
	}

	@Test
	void getConsumptionEventsEndpoint_EventTypeFilterReturnsOnlyMatchingType() throws Exception {
		LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
		jdbcTemplate.update("""
			insert into "consumption_events"
			("id", "inventory_item_id", "user_id", "event_type", "event_source_id", "quantity", "occurred_at", "created_at")
			values
			(1, 100, 42, 'consumed', 1, 2, ?, CURRENT_TIMESTAMP),
			(2, 100, 42, 'disposed', 1, 1, ?, CURRENT_TIMESTAMP)
			""", today.atStartOfDay(), today.atStartOfDay());

		mockMvc.perform(get("/api/me/consumption-events?eventType=disposed")
				.header("Authorization", "Bearer " + tokenService.issue(42L).accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.events.length()").value(1))
			.andExpect(jsonPath("$.data.events[0].eventId").value(2))
			.andExpect(jsonPath("$.data.events[0].eventType").value("disposed"));
	}

	@Test
	void getConsumptionEventsEndpoint_EventSourceConvertsAutoEstimatedCode() throws Exception {
		LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
		jdbcTemplate.update("""
			insert into "consumption_events"
			("id", "inventory_item_id", "user_id", "event_type", "event_source_id", "quantity", "occurred_at", "created_at")
			values (1, 100, 42, 'disposed', 2, 1, ?, CURRENT_TIMESTAMP)
			""", today.atStartOfDay());

		mockMvc.perform(get("/api/me/consumption-events")
				.header("Authorization", "Bearer " + tokenService.issue(42L).accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.events[0].eventSource").value("auto_estimated"));
	}

	@Test
	void getConsumptionEventsEndpoint_UserIsolation() throws Exception {
		LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
		jdbcTemplate.update("""
			insert into "consumption_events"
			("id", "inventory_item_id", "user_id", "event_type", "event_source_id", "quantity", "occurred_at", "created_at")
			values
			(1, 100, 42, 'consumed', 1, 2, ?, CURRENT_TIMESTAMP),
			(2, 100, 99, 'consumed', 1, 2, ?, CURRENT_TIMESTAMP)
			""", today.atStartOfDay(), today.atStartOfDay());

		mockMvc.perform(get("/api/me/consumption-events")
				.header("Authorization", "Bearer " + tokenService.issue(42L).accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.events.length()").value(1))
			.andExpect(jsonPath("$.data.events[0].eventId").value(1));
	}

	@Test
	void getConsumptionEventsEndpoint_Unauthorized() throws Exception {
		mockMvc.perform(get("/api/me/consumption-events"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.success").value(false));
	}

@Test
	void getConsumptionEventsEndpoint_ToDateInclusive() throws Exception {
		LocalDate fromDate = LocalDate.of(2026, 9, 10);
		LocalDate toDate = LocalDate.of(2026, 9, 10);

		jdbcTemplate.update("""
			insert into "consumption_events"
			("id", "inventory_item_id", "user_id", "event_type", "event_source_id", "quantity", "occurred_at", "created_at")
			values
			(1, 100, 42, 'consumed', 1, 1, ?, CURRENT_TIMESTAMP),
			(2, 100, 42, 'consumed', 1, 1, ?, CURRENT_TIMESTAMP),
			(3, 100, 42, 'consumed', 1, 1, ?, CURRENT_TIMESTAMP)
			""",
			fromDate.atStartOfDay(),
			fromDate.atTime(12, 0, 0),
			fromDate.atTime(23, 59, 59));

		mockMvc.perform(get("/api/me/consumption-events?from=2026-09-10&to=2026-09-10")
				.header("Authorization", "Bearer " + tokenService.issue(42L).accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.events.length()").value(3));
	}

	@Test
	void getConsumptionEventsEndpoint_MidnightBoundary_IncludesLastNanosecondOfToDate() throws Exception {
		LocalDate day = LocalDate.of(2026, 9, 10);

		jdbcTemplate.update("""
			insert into "consumption_events"
			("id", "inventory_item_id", "user_id", "event_type", "event_source_id", "quantity", "occurred_at", "created_at")
			values (1, 100, 42, 'consumed', 1, 1, ?, CURRENT_TIMESTAMP)
			""", day.atTime(23, 59, 59, 999_000_000));

		mockMvc.perform(get("/api/me/consumption-events?from=2026-09-10&to=2026-09-10")
				.header("Authorization", "Bearer " + tokenService.issue(42L).accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.events.length()").value(1));
	}

	@Test
	void getConsumptionEventsEndpoint_MidnightBoundary_ExcludesNextDayMidnight() throws Exception {
		LocalDate day = LocalDate.of(2026, 9, 10);

		jdbcTemplate.update("""
			insert into "consumption_events"
			("id", "inventory_item_id", "user_id", "event_type", "event_source_id", "quantity", "occurred_at", "created_at")
			values (1, 100, 42, 'consumed', 1, 1, ?, CURRENT_TIMESTAMP)
			""", day.plusDays(1).atStartOfDay());

		mockMvc.perform(get("/api/me/consumption-events?from=2026-09-10&to=2026-09-10")
				.header("Authorization", "Bearer " + tokenService.issue(42L).accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.events.length()").value(0));
	}

	@Test
	void getConsumptionEventsEndpoint_MidnightBoundary_ExcludesJustBeforeFromDate() throws Exception {
		LocalDate day = LocalDate.of(2026, 9, 10);

		jdbcTemplate.update("""
			insert into "consumption_events"
			("id", "inventory_item_id", "user_id", "event_type", "event_source_id", "quantity", "occurred_at", "created_at")
			values (1, 100, 42, 'consumed', 1, 1, ?, CURRENT_TIMESTAMP)
			""", day.minusDays(1).atTime(23, 59, 59, 999_000_000));

		mockMvc.perform(get("/api/me/consumption-events?from=2026-09-10&to=2026-09-10")
				.header("Authorization", "Bearer " + tokenService.issue(42L).accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.events.length()").value(0));
	}

	@Test
	void getConsumptionEventsEndpoint_OccurredAtFormat() throws Exception {
		LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));

		// DB에는 Asia/Seoul 벽시계 시각(naive)으로 저장되어 있다고 가정: 12:30:45 KST
		jdbcTemplate.update("""
			insert into "consumption_events"
			("id", "inventory_item_id", "user_id", "event_type", "event_source_id", "quantity", "occurred_at", "created_at")
			values (1, 100, 42, 'consumed', 1, 2, ?, CURRENT_TIMESTAMP)
			""", today.atTime(12, 30, 45));

		// 응답은 UTC(Z)로 변환되어야 한다: 12:30:45 KST(UTC+9) → 03:30:45Z
		mockMvc.perform(get("/api/me/consumption-events")
				.header("Authorization", "Bearer " + tokenService.issue(42L).accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.events[0].occurredAt").exists())
			.andExpect(jsonPath("$.data.events[0].occurredAt").value(org.hamcrest.Matchers.endsWith("Z")))
			.andExpect(jsonPath("$.data.events[0].occurredAt").value(
				org.hamcrest.Matchers.containsString(today.toString() + "T03:30:45")));
	}

	@Test
	void autoDisposeSchedulerNotEnabledByDefault() throws Exception {
		mockMvc.perform(get("/api/me/consumption-events")
				.header("Authorization", "Bearer " + tokenService.issue(42L).accessToken()))
			.andExpect(status().isOk());
	}

	private void assertInventory(int quantity, int statusId) {
		assertThat(jdbcTemplate.queryForObject("select remaining_quantity from inventory_items where id = 100", BigDecimal.class))
			.isEqualByComparingTo(BigDecimal.valueOf(quantity));
		assertThat(jdbcTemplate.queryForObject("select status_id from inventory_items where id = 100", Integer.class))
			.isEqualTo(statusId);
	}
}
