package com.vium.dispose;

import com.vium.auth.service.TokenService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.dispose.entity.ConsumptionEvent;
import com.vium.dispose.repository.ConsumptionEventRepository;
import com.vium.dispose.service.DisposeService;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
		jdbcTemplate.update("delete from consumption_events");
		jdbcTemplate.update("delete from inventory_items");
		jdbcTemplate.update("delete from item_statuses");
		jdbcTemplate.update("insert into item_statuses (id, code, name) values (1, 'active', '보유'), (2, 'consumed', '소진'), (3, 'disposed', '폐기')");
		jdbcTemplate.update("""
			insert into inventory_items
			(id, user_id, custom_name, status_id, unit_id, initial_quantity, remaining_quantity, created_at, updated_at)
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
	void eventQueryPreservesUserDateTypeFiltersAndDescendingOrder() {
		jdbcTemplate.update("""
			insert into consumption_events
			(id, inventory_item_id, user_id, event_type, event_source_id, quantity, occurred_at, created_at)
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

	private void assertInventory(int quantity, int statusId) {
		assertThat(jdbcTemplate.queryForObject("select remaining_quantity from inventory_items where id = 100", BigDecimal.class))
			.isEqualByComparingTo(BigDecimal.valueOf(quantity));
		assertThat(jdbcTemplate.queryForObject("select status_id from inventory_items where id = 100", Integer.class))
			.isEqualTo(statusId);
	}
}
