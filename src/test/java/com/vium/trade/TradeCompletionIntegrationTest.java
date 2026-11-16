package com.vium.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.post.entity.PostType;
import com.vium.trade.service.TradeSystemEvent;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

class TradeCompletionIntegrationTest extends TradeIntegrationSupport {
	@ParameterizedTest
	@EnumSource(PostType.class)
	void completesInventoryEventAndCreditAtomically(PostType type) throws Exception {
		var post = createPost(SELLER, type);
		long id = accepted(post, BUYER);
		var result = mvc.perform(post(endpoint(id, "complete")).header("Authorization", bearer(BUYER)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("COMPLETED"))
			.andExpect(jsonPath("$.data.postStatus").value("COMPLETED"))
			.andExpect(jsonPath("$.data.heldCredit").value(0))
			.andExpect(jsonPath("$.data.creditTransferred").value(type == PostType.SALE ? 3 : 0)).andReturn();
		long buyerItemId = mapper.readTree(result.getResponse().getContentAsString()).get("data").get("buyerInventoryItemId").asLong();
		var source = items.findById(post.getInventoryItemId()).orElseThrow();
		assertThat(source.getStatusId()).isEqualTo((short) 4);
		assertThat(source.getRemainingQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
		var target = items.findById(buyerItemId).orElseThrow();
		assertThat(target.getUserId()).isEqualTo(BUYER);
		assertThat(target.getIngredientCatalogId()).isEqualTo(1L);
		assertThat(target.getCustomName()).isEqualTo("서울 우유");
		assertThat(target.getUnitId()).isEqualTo(source.getUnitId());
		assertThat(target.getStorageMethodId()).isEqualTo(source.getStorageMethodId());
		assertThat(target.getInitialQuantity()).isEqualByComparingTo("2.500");
		assertThat(target.getRemainingQuantity()).isEqualByComparingTo("2.500");
		assertThat(target.getAmount()).isNull();
		assertThat(target.getImageId()).isNull();
		assertThat(target.getPurchaseItemId()).isNull();
		assertThat(target.getStatusId()).isEqualTo((short) 1);
		assertThat(target.getPurchasedOn()).isEqualTo(TODAY);
		assertThat(target.getExpiresOn()).isEqualTo(source.getExpiresOn());
		assertThat(jdbc.queryForObject("select buyer_inventory_item_id from trades where id=?", Long.class, id)).isEqualTo(buyerItemId);
		assertThat(jdbc.queryForObject("select quantity from consumption_events where inventory_item_id=? and event_type='shared'", BigDecimal.class, source.getId()))
			.isEqualByComparingTo("2.5");
		assertThat(jdbc.queryForObject("select amount from consumption_events where inventory_item_id=?", BigDecimal.class, source.getId()))
			.isEqualByComparingTo("5000");
		assertThat(jdbc.queryForObject("select event_source_id from consumption_events where inventory_item_id=?", Short.class, source.getId())).isEqualTo((short) 1);
		assertThat(credits.getAvailableBalance(SELLER)).isEqualTo(type == PostType.SALE ? 104 : 101);
		assertThat(ledgerCount("SAVING_BONUS")).isEqualTo(1);
		assertThat(ledgerCount("TRADE_INCOME")).isEqualTo(type == PostType.SALE ? 1 : 0);
		assertThat(credits.getHeldBalance(BUYER)).isZero();
		long ledgerRows = count("credit_transactions");
		mvc.perform(post(endpoint(id, "complete")).header("Authorization", bearer(BUYER)))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INVALID_TRADE_STATUS"));
		assertThat(count("credit_transactions")).isEqualTo(ledgerRows);
		assertThat(count("consumption_events")).isEqualTo(1);
		assertThat(inventory.list(SELLER, false).ingredients()).isEmpty();
		mvc.perform(patch("/api/me/ingredients/" + buyerItemId + "/status").header("Authorization", bearer(BUYER))
			.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"consumed\",\"quantity\":2.5}"))
			.andExpect(status().isOk());
	}

	@Test
	void permissionsAndRequestedState() throws Exception {
		long id = request(createPost(SELLER, PostType.SALE), BUYER);
		mvc.perform(post(endpoint(id, "complete")).header("Authorization", bearer(SELLER)))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("TRADE_FORBIDDEN"));
		mvc.perform(post(endpoint(id, "complete")).header("Authorization", bearer(OTHER)))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("TRADE_NOT_FOUND"));
		mvc.perform(post(endpoint(id, "complete")).header("Authorization", bearer(BUYER)))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INVALID_TRADE_STATUS"));
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void failuresAtInventoryCreationOrNotificationRollBackEverything(boolean failNotification) throws Exception {
		var post = createPost(SELLER, PostType.SALE);
		long id = accepted(post, BUYER);
		if (failNotification) {
			doThrow(new IllegalStateException("failed")).when(notifier).notify(any(), eq(TradeSystemEvent.TRADE_COMPLETED));
		} else {
			doThrow(new IllegalStateException("failed")).when(inventory).registerFromTrade(eq(BUYER), any(), any(LocalDate.class));
		}
		mvc.perform(post(endpoint(id, "complete")).header("Authorization", bearer(BUYER))).andExpect(status().isInternalServerError());
		assertThat(tradeStatus(id)).isEqualTo("ACCEPTED");
		assertThat(postStatus(post)).isEqualTo("RESERVED");
		var source = items.findById(post.getInventoryItemId()).orElseThrow();
		assertThat(source.getStatusId()).isEqualTo((short) 1);
		assertThat(source.getRemainingQuantity()).isEqualByComparingTo("2.5");
		assertThat(count("inventory_items")).isEqualTo(1);
		assertThat(count("consumption_events")).isZero();
		assertThat(ledgerCount("TRADE_INCOME")).isZero();
		assertThat(ledgerCount("SAVING_BONUS")).isZero();
		assertThat(credits.getHeldBalance(BUYER)).isEqualTo(3);
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 7, 8})
	void bonusExcludesSamePairInEitherDirectionForSevenDays(int daysAgo) {
		long previous = completed(BUYER, SELLER);
		jdbc.update("update trades set completed_at=? where id=?", LOCAL_NOW.minusDays(daysAgo), previous);
		completed(SELLER, BUYER);
		assertThat(jdbc.queryForObject("select count(*) from credit_transactions where user_id=? and type='SAVING_BONUS'", Long.class, SELLER))
			.isEqualTo(daysAgo <= 7 ? 0 : 1);
	}

	@Test
	void bonusIsLimitedToThreeDistinctPartnersPerDay() {
		for (long buyer : new long[]{42, 44, 45, 46}) completed(SELLER, buyer);
		assertThat(ledgerCount("SAVING_BONUS")).isEqualTo(3);
	}

	@Test
	void simultaneousOppositeDirectionCompletionsGiveOnlyOnePairBonus() throws Exception {
		long first = accepted(createPost(SELLER, PostType.SHARE), BUYER);
		long second = accepted(createPost(BUYER, PostType.SHARE), SELLER);
		assertThat(race(() -> { trades.complete(BUYER, first); return "OK"; },
			() -> { trades.complete(SELLER, second); return "OK"; })).containsExactly("OK", "OK");
		assertThat(ledgerCount("SAVING_BONUS")).isEqualTo(1);
		assertThat(count("consumption_events")).isEqualTo(2);
	}

	@Test
	void concurrentCompletionDoesNotDuplicateInventoryOrIncome() throws Exception {
		long id = accepted(createPost(SELLER, PostType.SALE), BUYER);
		java.util.concurrent.Callable<String> complete = () -> String.valueOf(mvc.perform(post(endpoint(id, "complete"))
			.header("Authorization", bearer(BUYER))).andReturn().getResponse().getStatus());
		assertThat(race(complete, complete)).containsExactlyInAnyOrder("200", "409");
		assertThat(count("inventory_items")).isEqualTo(2);
		assertThat(ledgerCount("TRADE_INCOME")).isEqualTo(1);
		assertThat(ledgerCount("SAVING_BONUS")).isEqualTo(1);
	}
}
