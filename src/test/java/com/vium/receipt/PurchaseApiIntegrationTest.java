package com.vium.receipt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.vium.auth.service.TokenService;
import com.vium.receipt.entity.*;
import com.vium.receipt.repository.*;
import com.vium.inventory.repository.InventoryItemRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;
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
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:purchase-api;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc
class PurchaseApiIntegrationTest {
	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired TokenService tokens;
	@Autowired PurchaseRepository purchases;
	@Autowired PurchaseItemRepository items;
	@Autowired InventoryItemRepository inventory;
	@Autowired JsonMapper mapper;
	private String bearer;
	private Purchase draft;
	private PurchaseItem original;
	private static final LocalDateTime DATE = LocalDateTime.of(2026, 1, 20, 12, 0);

	@BeforeEach
	void setup() {
		for (String table : List.of("consumption_events", "inventory_items", "purchase_items", "purchases",
			"expiry_estimation_rules", "ingredient_catalog", "purchase_sources", "storage_methods", "units", "item_statuses", "uploaded_images")) {
			jdbc.update("delete from " + table);
		}
		jdbc.update("insert into units (id,code,name) values (1,'ea','개')");
		jdbc.update("insert into storage_methods (id,code,name) values (1,'cold','냉장')");
		jdbc.update("insert into item_statuses (id,code,name) values (1,'active','보유중')");
		jdbc.update("insert into purchase_sources (id,code,name) values (1,'emart','이마트')");
		jdbc.update("insert into ingredient_catalog (id,default_unit_id,name,created_at) values (1,1,'우유',CURRENT_TIMESTAMP)");
		jdbc.update("insert into expiry_estimation_rules (id,ingredient_catalog_id,storage_method_id,shelf_life_days,effective_from) values (1,1,1,7,DATE '2026-01-01')");
		bearer = "Bearer " + tokens.issue(42L).accessToken();
		draft = draft(42, PurchaseType.RECEIPT, DATE);
		original = items.save(PurchaseItem.builder().purchaseId(draft.getId()).ingredientCatalogId(1L)
			.rawName("서울우유").quantity(BigDecimal.ONE).unitId((short) 1).amount(new BigDecimal("1000")).build());
	}

	@Test
	void confirmsReplacesItemsAndLinksInventoryUsingSeoulPurchaseDate() throws Exception {
		String body = """
			{"purchaseSourceId":1,"purchasedAt":"2026-01-20T18:00:00Z","items":[
			{"purchaseItemId":%d,"ingredientCatalogId":1,"quantity":2,"unitId":1,"storageMethodId":1,"amount":2000},
			{"customName":"양파","quantity":3,"unitId":1,"expiresOn":"2026-01-30","amount":3000}]}
			""".formatted(original.getId());
		confirm(draft.getId(), body).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("CONFIRMED"))
			.andExpect(jsonPath("$.data.ingredients.length()").value(2))
			.andExpect(jsonPath("$.data.ingredients[0].purchasedOn").value("2026-01-21"))
			.andExpect(jsonPath("$.data.ingredients[0].expiresOn").value("2026-01-28"));
		assertThat(items.existsById(original.getId())).isFalse();
		var saved = items.findAllByPurchaseIdOrderByIdAsc(draft.getId());
		assertThat(saved).extracting(PurchaseItem::getRawName).containsExactly("서울우유", "양파");
		assertThat(inventory.findAll()).hasSize(2).allSatisfy(item -> {
			assertThat(item.getUserId()).isEqualTo(42);
			assertThat(saved).extracting(PurchaseItem::getId).contains(item.getPurchaseItemId());
		});
		assertThat(purchases.findById(draft.getId()).orElseThrow().getTotalAmount()).isEqualByComparingTo("5000");
		assertThat(jdbc.queryForObject("select count(*) from consumption_events", Integer.class)).isZero();
		mvc.perform(get("/api/me/purchases/" + draft.getId()).header("Authorization", bearer))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].ingredientName").value("우유"))
			.andExpect(jsonPath("$.data.items[0].expiresOn").value("2026-01-28"));
	}

	@Test
	void repeatConfirmationReturnsConflictWithoutNewInventory() throws Exception {
		confirm(draft.getId(), valid()).andExpect(status().isOk());
		confirm(draft.getId(), valid()).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("PURCHASE_ALREADY_CONFIRMED"));
		assertThat(inventory.count()).isEqualTo(1);
		assertThat(purchases.findById(draft.getId()).orElseThrow().getTotalAmount()).isEqualByComparingTo("1000");
	}

	@Test
	void laterInvalidItemRollsBackReplacementAndPreviouslyCreatedInventory() throws Exception {
		String body = """
			{"purchasedAt":"2026-01-20T12:00:00+09:00","items":[
			{"ingredientCatalogId":1,"quantity":1,"unitId":1,"storageMethodId":1},
			{"customName":"미매칭 품목","quantity":1,"unitId":1}]}
			""";
		confirm(draft.getId(), body).andExpect(status().isBadRequest());
		assertThat(inventory.count()).isZero();
		assertThat(items.findAll()).singleElement().satisfies(item -> assertThat(item.getId()).isEqualTo(original.getId()));
		assertThat(purchases.findById(draft.getId()).orElseThrow().isDraft()).isTrue();
	}

	@Test
	void rejectsItemFromAnotherPurchase() throws Exception {
		var other = draft(42, PurchaseType.RECEIPT, DATE);
		String body = valid().replace("\"ingredientCatalogId\":1", "\"purchaseItemId\":" + original.getId() + ",\"ingredientCatalogId\":1");
		confirm(other.getId(), body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
		assertThat(inventory.count()).isZero();
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"{\"purchasedAt\":\"2099-01-01T00:00:00Z\",\"items\":[{\"quantity\":1,\"unitId\":1}]}",
		"{\"purchasedAt\":\"2026-01-20T00:00:00Z\",\"items\":[]}",
		"{\"purchasedAt\":\"2026-01-20T00:00:00Z\",\"items\":[null]}",
		"{\"purchasedAt\":\"2026-01-20T00:00:00Z\",\"items\":[{\"quantity\":0,\"unitId\":1}]}",
		"{\"purchasedAt\":\"2026-01-20T00:00:00Z\",\"items\":[{\"quantity\":1.0001,\"unitId\":1}]}",
		"{\"purchasedAt\":\"2026-01-20T00:00:00Z\",\"items\":[{\"quantity\":1,\"unitId\":1,\"amount\":-1}]}"
	})
	void rejectsInvalidConfirmRequest(String body) throws Exception {
		confirm(draft.getId(), body).andExpect(status().isBadRequest());
		assertThat(inventory.count()).isZero();
		assertThat(items.existsById(original.getId())).isTrue();
	}

	@Test
	void rejectsMissingSourceAndTooManyItems() throws Exception {
		confirm(draft.getId(), valid().replace("\"purchasedAt\"", "\"purchaseSourceId\":999,\"purchasedAt\""))
			.andExpect(status().isBadRequest());
		var body = (tools.jackson.databind.node.ObjectNode) mapper.readTree(valid());
		var item = body.get("items").get(0);
		var array = body.putArray("items");
		for (int i = 0; i < 101; i++) array.add(item);
		confirm(draft.getId(), body.toString()).andExpect(status().isBadRequest());
	}

	@Test
	void protectsOtherUsersAndMissingPurchases() throws Exception {
		var other = draft(43, PurchaseType.RECEIPT, DATE);
		for (long id : List.of(other.getId(), Long.MAX_VALUE)) {
			confirm(id, valid()).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("PURCHASE_NOT_FOUND"));
			mvc.perform(get("/api/me/purchases/" + id).header("Authorization", bearer)).andExpect(status().isNotFound());
			mvc.perform(delete("/api/me/purchases/" + id).header("Authorization", bearer)).andExpect(status().isNotFound());
		}
	}

	@Test
	void listsWithInclusiveDateFilterAndStableLatestFirstOrdering() throws Exception {
		var sameTime = draft(42, PurchaseType.ONLINE_ORDER, DATE);
		draft(43, PurchaseType.RECEIPT, DATE.plusHours(1));
		draft(42, PurchaseType.RECEIPT, DATE.plusDays(1).toLocalDate().atStartOfDay());
		mvc.perform(get("/api/me/purchases").header("Authorization", bearer).param("from", "2026-01-20").param("to", "2026-01-20"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.purchases.length()").value(2))
			.andExpect(jsonPath("$.data.purchases[0].purchaseId").value(sameTime.getId()))
			.andExpect(jsonPath("$.data.purchases[1].itemCount").value(1))
			.andExpect(jsonPath("$.data.purchases[1].purchasedAt").value("2026-01-20T12:00:00+09:00"));
		confirm(draft.getId(), valid()).andExpect(status().isOk());
		mvc.perform(get("/api/me/purchases").header("Authorization", bearer).param("type", "RECEIPT").param("status", "CONFIRMED"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.purchases.length()").value(1));
	}

	@ParameterizedTest
	@ValueSource(strings = {"from=2026-02-01&to=2026-01-01", "from=invalid", "type=INVALID", "status=INVALID"})
	void rejectsInvalidListFilter(String query) throws Exception {
		mvc.perform(get("/api/me/purchases?" + query).header("Authorization", bearer)).andExpect(status().isBadRequest());
	}

	@Test
	void deletesDraftItemsButPreservesImageAndRefusesConfirmedDeletion() throws Exception {
		jdbc.update("insert into uploaded_images (id,user_id,purpose,bucket,object_key,content_type,size_bytes,created_at,ready) values (900,42,'RECEIPT','private','test','image/png',1,CURRENT_TIMESTAMP,true)");
		jdbc.update("update purchases set image_id=900 where id=?", draft.getId());
		mvc.perform(delete("/api/me/purchases/" + draft.getId()).header("Authorization", bearer))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data").value(nullValue()));
		assertThat(items.count()).isZero();
		assertThat(purchases.existsById(draft.getId())).isFalse();
		assertThat(jdbc.queryForObject("select count(*) from uploaded_images", Integer.class)).isEqualTo(1);
		var other = draft(42, PurchaseType.RECEIPT, DATE);
		confirm(other.getId(), valid()).andExpect(status().isOk());
		mvc.perform(delete("/api/me/purchases/" + other.getId()).header("Authorization", bearer))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("PURCHASE_ALREADY_CONFIRMED"));
		assertThat(inventory.count()).isEqualTo(1);
	}

	@Test
	void concurrentConfirmCreatesInventoryExactlyOnce() throws Exception {
		var start = new CountDownLatch(1);
		try (var pool = Executors.newFixedThreadPool(2)) {
			Callable<Integer> task = () -> { start.await(); return confirm(draft.getId(), valid()).andReturn().getResponse().getStatus(); };
			var a = pool.submit(task); var b = pool.submit(task); start.countDown();
			assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
		}
		assertThat(inventory.count()).isEqualTo(1);
		assertThat(items.count()).isEqualTo(1);
	}

	@Test
	void allEndpointsRequireAuthentication() throws Exception {
		mvc.perform(get("/api/me/purchases")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/me/purchases/" + draft.getId())).andExpect(status().isUnauthorized());
		mvc.perform(delete("/api/me/purchases/" + draft.getId())).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/me/purchases/" + draft.getId() + "/confirm").contentType(MediaType.APPLICATION_JSON).content(valid()))
			.andExpect(status().isUnauthorized());
	}

	private Purchase draft(long owner, PurchaseType type, LocalDateTime date) {
		return purchases.save(Purchase.draft(owner, type, null, (short) 1, new BigDecimal("1000"), date, DATE));
	}
	private String valid() {
		return "{\"purchasedAt\":\"2026-01-20T12:00:00+09:00\",\"items\":[{\"ingredientCatalogId\":1,\"quantity\":1,\"unitId\":1,\"storageMethodId\":1}]}";
	}
	private ResultActions confirm(long id, String body) throws Exception {
		return mvc.perform(post("/api/me/purchases/" + id + "/confirm").header("Authorization", bearer)
			.contentType(MediaType.APPLICATION_JSON).content(body));
	}
}
