package com.vium.inventory.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class InventoryUpdateIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private static final String BODY = """
		{"customName":"  수정한 재료  ","quantity":8,"unitId":1,"storageMethodId":2,
		 "purchasedOn":"2026-01-02","expiresOn":"2026-01-10"}
		""";

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("insert into units (id, code, name) values (1,'ea','개'),(2,'g','그램')");
		jdbcTemplate.update("insert into storage_methods (id, code, name) values (1,'cold','냉장'),(2,'frozen','냉동')");
		jdbcTemplate.update("insert into item_statuses (id, code, name) values (1,'active','보유중'),(2,'consumed','소진')");
		jdbcTemplate.update("""
			insert into inventory_items
			(id,user_id,custom_name,status_id,unit_id,storage_method_id,initial_quantity,remaining_quantity,
			 amount,purchased_on,expires_on,created_at,updated_at)
			values (100,1,'기존 재료',1,1,1,10,10,5000,DATE '2026-01-01',DATE '2026-01-05',
			 TIMESTAMP '2026-01-01 00:00:00',TIMESTAMP '2026-01-01 00:00:00')
			""");
	}

	@Test
	void updatesAllFieldsAndPersistsWithoutChangingAmountOrStatus() throws Exception {
		mockMvc.perform(patch("/api/me/ingredients/100").contentType(MediaType.APPLICATION_JSON).content(BODY))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.error").value(nullValue()))
			.andExpect(jsonPath("$.data.inventoryItemId").value(100))
			.andExpect(jsonPath("$.data.customName").value("수정한 재료"))
			.andExpect(jsonPath("$.data.quantity").value(8))
			.andExpect(jsonPath("$.data.unitId").value(1))
			.andExpect(jsonPath("$.data.storageMethodId").value(2))
			.andExpect(jsonPath("$.data.purchasedOn").value("2026-01-02"))
			.andExpect(jsonPath("$.data.expiresOn").value("2026-01-10"))
			.andExpect(jsonPath("$.data.statusCode").value("active"));
		flush();
		assertQuantity("8", "8");
		assertThat(jdbcTemplate.queryForObject("select custom_name from inventory_items where id=100",String.class)).isEqualTo("수정한 재료");
		assertThat(jdbcTemplate.queryForObject("select amount from inventory_items where id=100",BigDecimal.class)).isEqualByComparingTo("5000");
		assertThat(jdbcTemplate.queryForObject("select count(*) from consumption_events",Long.class)).isZero();
	}

	@Test
	void preservesProcessedQuantityAndExistingEvent() throws Exception {
		addEvent();
		mockMvc.perform(patch("/api/me/ingredients/100").contentType(MediaType.APPLICATION_JSON).content(BODY))
			.andExpect(status().isOk());
		flush();
		assertQuantity("8","5");
		assertThat(jdbcTemplate.queryForObject("select quantity from consumption_events where id=100",BigDecimal.class)).isEqualByComparingTo("3");
		assertThat(jdbcTemplate.queryForObject("select count(*) from consumption_events",Long.class)).isEqualTo(1L);
	}

	@Test
	void rejectsQuantityBelowProcessedAmountWithoutChangingFields() throws Exception {
		addEvent();
		mockMvc.perform(patch("/api/me/ingredients/100").contentType(MediaType.APPLICATION_JSON).content(BODY.replace("\"quantity\":8","\"quantity\":2")))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
		flush();
		assertQuantity("10","7");
		assertThat(jdbcTemplate.queryForObject("select custom_name from inventory_items where id=100",String.class)).isEqualTo("기존 재료");
	}

	@Test
	void allowsQuantityEqualToProcessedAmountWithoutChangingStatus() throws Exception {
		addEvent();
		mockMvc.perform(patch("/api/me/ingredients/100").contentType(MediaType.APPLICATION_JSON).content(BODY.replace("\"quantity\":8","\"quantity\":3")))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.statusCode").value("active"));
		flush();
		assertQuantity("3","0");
	}

	@Test
	void rejectsUnitChangeWhenAnEventExistsEvenWithZeroProcessedQuantity() throws Exception {
		addEvent();
		jdbcTemplate.update("update inventory_items set remaining_quantity=10 where id=100");
		mockMvc.perform(patch("/api/me/ingredients/100").contentType(MediaType.APPLICATION_JSON).content(BODY.replace("\"unitId\":1","\"unitId\":2")))
			.andExpect(status().isBadRequest());
	}

	@Test
	void allowsUnitChangeWithoutHistory() throws Exception {
		mockMvc.perform(patch("/api/me/ingredients/100").contentType(MediaType.APPLICATION_JSON).content(BODY.replace("\"unitId\":1","\"unitId\":2")))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.unitId").value(2));
	}

	@Test
	void allowsExplicitNullNameForCatalogIngredientAndKeepsStatus() throws Exception {
		jdbcTemplate.update("insert into ingredient_catalog (id,default_unit_id,name,created_at) values (100,1,'우유',CURRENT_TIMESTAMP)");
		jdbcTemplate.update("update inventory_items set ingredient_catalog_id=100,status_id=2 where id=100");
		mockMvc.perform(patch("/api/me/ingredients/100").contentType(MediaType.APPLICATION_JSON).content(BODY.replace("\"  수정한 재료  \"","null")))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.customName").value(nullValue()))
			.andExpect(jsonPath("$.data.statusCode").value("consumed"));
	}

	@ParameterizedTest
	@ValueSource(strings={"customName","quantity","unitId","storageMethodId","purchasedOn","expiresOn"})
	void rejectsMissingRequiredFields(String field) throws Exception {
		String body=BODY.replaceAll("\\\""+field+"\\\"\\s*:\\s*(\\\"[^\\\"]*\\\"|[0-9]+)\\s*,?", "").replaceAll(",\\s*}","}");
		mockMvc.perform(patch("/api/me/ingredients/100").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@ParameterizedTest
	@ValueSource(strings={"0","-1","1.0001","1000000000"})
	void rejectsInvalidQuantity(String quantity) throws Exception {
		mockMvc.perform(patch("/api/me/ingredients/100").contentType(MediaType.APPLICATION_JSON).content(BODY.replace("\"quantity\":8","\"quantity\":"+quantity)))
			.andExpect(status().isBadRequest());
	}

	@ParameterizedTest
	@ValueSource(strings={"unitId","storageMethodId"})
	void rejectsUnknownCode(String field) throws Exception {
		String body=BODY.replaceAll("\\\""+field+"\\\":[0-9]+","\""+field+"\":99");
		mockMvc.perform(patch("/api/me/ingredients/100").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isBadRequest());
	}

	@ParameterizedTest
	@ValueSource(strings={"null","\"   \""})
	void rejectsMissingIdentityForCustomIngredient(String name) throws Exception {
		mockMvc.perform(patch("/api/me/ingredients/100").contentType(MediaType.APPLICATION_JSON).content(BODY.replace("\"  수정한 재료  \"",name)))
			.andExpect(status().isBadRequest());
	}

	@ParameterizedTest
	@ValueSource(strings={"2026-01-01","invalid-date"})
	void rejectsInvalidExpiry(String date) throws Exception {
		mockMvc.perform(patch("/api/me/ingredients/100").contentType(MediaType.APPLICATION_JSON).content(BODY.replace("2026-01-10",date)))
			.andExpect(status().isBadRequest());
	}

	@Test
	void rejectsMissingAndOtherUsersItems() throws Exception {
		mockMvc.perform(patch("/api/me/ingredients/999").contentType(MediaType.APPLICATION_JSON).content(BODY))
			.andExpect(status().isNotFound());
		jdbcTemplate.update("update inventory_items set user_id=2 where id=100");
		mockMvc.perform(patch("/api/me/ingredients/100").contentType(MediaType.APPLICATION_JSON).content(BODY))
			.andExpect(status().isNotFound());
		assertQuantity("10","10");
	}

	@Autowired
	private jakarta.persistence.EntityManager entityManager;

	private void flush() { entityManager.flush(); entityManager.clear(); }

	private void addEvent() {
		jdbcTemplate.update("update inventory_items set remaining_quantity=7 where id=100");
		jdbcTemplate.update("""
			insert into consumption_events (id,inventory_item_id,user_id,event_type,event_source_id,quantity,occurred_at,created_at)
			values (100,100,1,'consumed',1,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
			""");
	}

	private void assertQuantity(String initial,String remaining) {
		assertThat(jdbcTemplate.queryForObject("select initial_quantity from inventory_items where id=100",BigDecimal.class)).isEqualByComparingTo(initial);
		assertThat(jdbcTemplate.queryForObject("select remaining_quantity from inventory_items where id=100",BigDecimal.class)).isEqualByComparingTo(remaining);
	}
}
