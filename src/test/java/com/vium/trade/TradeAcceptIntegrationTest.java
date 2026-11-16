package com.vium.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.post.entity.PostType;
import com.vium.trade.service.TradeSystemEvent;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class TradeAcceptIntegrationTest extends TradeIntegrationSupport {
	@Test
	void sellerAcceptsAndSets72HourDeadline() throws Exception {
		var post = createPost(SELLER, PostType.SALE);
		long id = request(post, BUYER);
		mvc.perform(post(endpoint(id, "accept")).header("Authorization", bearer(SELLER)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ACCEPTED"))
			.andExpect(jsonPath("$.data.postStatus").value("RESERVED"))
			.andExpect(jsonPath("$.data.heldCredit").value(3));
		assertThat(jdbc.queryForObject("select accepted_at from trades where id=?", LocalDateTime.class, id)).isEqualTo(LOCAL_NOW);
		assertThat(jdbc.queryForObject("select auto_resolve_at from trades where id=?", LocalDateTime.class, id)).isEqualTo(LOCAL_NOW.plusHours(72));
		assertThat(credits.getAvailableBalance(BUYER)).isEqualTo(97);
		verify(notifier).notify(any(), eq(TradeSystemEvent.TRADE_ACCEPTED));
		mvc.perform(post(endpoint(id, "accept")).header("Authorization", bearer(SELLER)))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INVALID_TRADE_STATUS"));
	}

	@Test
	void permissionsAndMissingTrade() throws Exception {
		long id = request(createPost(SELLER, PostType.SHARE), BUYER);
		mvc.perform(post(endpoint(id, "accept")).header("Authorization", bearer(BUYER)))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("TRADE_FORBIDDEN"));
		mvc.perform(post(endpoint(id, "accept")).header("Authorization", bearer(OTHER)))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("TRADE_NOT_FOUND"));
		mvc.perform(post(endpoint(99999, "accept")).header("Authorization", bearer(SELLER)))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("TRADE_NOT_FOUND"));
	}

	@Test
	void expiryCommitsCancellationAndRefundDespite409() throws Exception {
		var post = createPost(SELLER, PostType.SALE);
		long id = request(post, BUYER);
		expire(post);
		mvc.perform(post(endpoint(id, "accept")).header("Authorization", bearer(SELLER)))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("POST_EXPIRED"));
		assertThat(tradeStatus(id)).isEqualTo("CANCELED");
		assertThat(postStatus(post)).isEqualTo("EXPIRED");
		assertThat(credits.getAvailableBalance(BUYER)).isEqualTo(100);
		assertThat(credits.getHeldBalance(BUYER)).isZero();
		assertThat(jdbc.queryForObject("select canceled_by_id from trades where id=?", Long.class, id)).isNull();
		verify(notifier).notify(any(), eq(TradeSystemEvent.TRADE_CANCELED));
	}

	@Test
	void alreadyExpiredShareCancelsWithoutLedgerEntries() throws Exception {
		var post = createPost(SELLER, PostType.SHARE);
		long id = request(post, BUYER);
		jdbc.update("update posts set status='EXPIRED' where id=?", post.getId());
		mvc.perform(post(endpoint(id, "accept")).header("Authorization", bearer(SELLER)))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("POST_EXPIRED"));
		assertThat(tradeStatus(id)).isEqualTo("CANCELED");
		assertThat(ledgerCount("TRADE_REFUND")).isZero();
	}

	@Test
	void notificationFailureRollsBackAccept() throws Exception {
		var post = createPost(SELLER, PostType.SALE);
		long id = request(post, BUYER);
		doThrow(new IllegalStateException("failed")).when(notifier).notify(any(), eq(TradeSystemEvent.TRADE_ACCEPTED));
		mvc.perform(post(endpoint(id, "accept")).header("Authorization", bearer(SELLER))).andExpect(status().isInternalServerError());
		assertThat(tradeStatus(id)).isEqualTo("REQUESTED");
		assertThat(postStatus(post)).isEqualTo("OPEN");
	}

	@Test
	void concurrentAcceptOnlyNotifiesOnce() throws Exception {
		long id = request(createPost(SELLER, PostType.SHARE), BUYER);
		java.util.concurrent.Callable<String> accept = () -> String.valueOf(mvc.perform(
			post(endpoint(id, "accept")).header("Authorization", bearer(SELLER))).andReturn().getResponse().getStatus());
		assertThat(race(accept, accept)).containsExactlyInAnyOrder("200", "409");
		verify(notifier).notify(any(), eq(TradeSystemEvent.TRADE_ACCEPTED));
	}
}
