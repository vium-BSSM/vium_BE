package com.vium.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.post.entity.PostType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class TradeRejectIntegrationTest extends TradeIntegrationSupport {
	@ParameterizedTest
	@EnumSource(PostType.class)
	void rejectionRefundsOnlySaleAndKeepsOpen(PostType type) throws Exception {
		var post = createPost(SELLER, type);
		long id = request(post, BUYER);
		mvc.perform(post(endpoint(id, "reject")).header("Authorization", bearer(SELLER)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("REJECTED"))
			.andExpect(jsonPath("$.data.postStatus").value("OPEN")).andExpect(jsonPath("$.data.heldCredit").value(0));
		assertThat(credits.getAvailableBalance(BUYER)).isEqualTo(100);
		assertThat(credits.getHeldBalance(BUYER)).isZero();
		assertThat(ledgerCount("TRADE_REFUND")).isEqualTo(type == PostType.SALE ? 1 : 0);
		mvc.perform(post(endpoint(id, "reject")).header("Authorization", bearer(SELLER)))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INVALID_TRADE_STATUS"));
		assertThat(credits.getAvailableBalance(BUYER)).isEqualTo(100);
	}

	@Test
	void rejectsBuyerOutsiderAndAcceptedTrade() throws Exception {
		long id = request(createPost(SELLER, PostType.SALE), BUYER);
		mvc.perform(post(endpoint(id, "reject")).header("Authorization", bearer(BUYER)))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("TRADE_FORBIDDEN"));
		mvc.perform(post(endpoint(id, "reject")).header("Authorization", bearer(OTHER)))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("TRADE_NOT_FOUND"));
		trades.accept(SELLER, id);
		mvc.perform(post(endpoint(id, "reject")).header("Authorization", bearer(SELLER)))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INVALID_TRADE_STATUS"));
		assertThat(credits.getHeldBalance(BUYER)).isEqualTo(3);
	}
}
