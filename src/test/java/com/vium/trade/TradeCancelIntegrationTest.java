package com.vium.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.post.entity.PostType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

class TradeCancelIntegrationTest extends TradeIntegrationSupport {
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void requestedOrAcceptedCancellationRefundsAndOpens(boolean accepted) throws Exception {
		var post = createPost(SELLER, PostType.SALE);
		long id = request(post, BUYER);
		if (accepted) trades.accept(SELLER, id);
		long actor = accepted ? SELLER : BUYER;
		mvc.perform(post(endpoint(id, "cancel")).header("Authorization", bearer(actor))
			.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"일정 변경\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELED"))
			.andExpect(jsonPath("$.data.postStatus").value("OPEN")).andExpect(jsonPath("$.data.heldCredit").value(0));
		assertThat(credits.getAvailableBalance(BUYER)).isEqualTo(100);
		assertThat(jdbc.queryForObject("select cancel_reason from trades where id=?", String.class, id)).isEqualTo("일정 변경");
		assertThat(jdbc.queryForObject("select canceled_by_id from trades where id=?", Long.class, id)).isEqualTo(actor);
	}

	@Test
	void expiredAcceptedCancellationAndOptionalBody() throws Exception {
		var post = createPost(SELLER, PostType.SALE);
		long id = accepted(post, BUYER);
		expire(post);
		mvc.perform(post(endpoint(id, "cancel")).header("Authorization", bearer(BUYER)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.postStatus").value("EXPIRED"));
		assertThat(credits.getAvailableBalance(BUYER)).isEqualTo(100);
	}

	@Test
	void rejectsOutsiderAndLongReasonAndCompletedTrade() throws Exception {
		long id = request(createPost(SELLER, PostType.SALE), BUYER);
		mvc.perform(post(endpoint(id, "cancel")).header("Authorization", bearer(OTHER)))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("TRADE_NOT_FOUND"));
		mvc.perform(post(endpoint(id, "cancel")).header("Authorization", bearer(BUYER))
			.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(java.util.Map.of("reason", "a".repeat(201)))))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
		trades.accept(SELLER, id);
		trades.complete(BUYER, id);
		mvc.perform(post(endpoint(id, "cancel")).header("Authorization", bearer(BUYER)))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INVALID_TRADE_STATUS"));
	}

	@Test
	void simultaneousRejectAndCancelRefundOnce() throws Exception {
		long id = request(createPost(SELLER, PostType.SALE), BUYER);
		assertThat(race(
			() -> String.valueOf(mvc.perform(post(endpoint(id, "reject")).header("Authorization", bearer(SELLER))).andReturn().getResponse().getStatus()),
			() -> String.valueOf(mvc.perform(post(endpoint(id, "cancel")).header("Authorization", bearer(BUYER))).andReturn().getResponse().getStatus())))
			.containsExactlyInAnyOrder("200", "409");
		assertThat(ledgerCount("TRADE_REFUND")).isEqualTo(1);
		assertThat(credits.getAvailableBalance(BUYER)).isEqualTo(100);
	}
}
