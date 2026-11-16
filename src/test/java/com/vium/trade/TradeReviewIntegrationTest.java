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

class TradeReviewIntegrationTest extends TradeIntegrationSupport {
	@Test
	void eachParticipantCanReviewPartnerOnce() throws Exception {
		long id = completed(SELLER, BUYER);
		for (long writer : new long[]{BUYER, SELLER}) {
			mvc.perform(post(endpoint(id, "review")).header("Authorization", bearer(writer))
				.contentType(MediaType.APPLICATION_JSON).content("{\"mannerScore\":5,\"content\":\"감사합니다\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.tradeId").value(id))
				.andExpect(jsonPath("$.data.reviewId").isNumber()).andExpect(jsonPath("$.data.mannerScore").value(5));
			mvc.perform(post(endpoint(id, "review")).header("Authorization", bearer(writer))
				.contentType(MediaType.APPLICATION_JSON).content("{\"mannerScore\":4}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("REVIEW_ALREADY_WRITTEN"));
		}
		assertThat(count("trade_reviews")).isEqualTo(2);
		assertThat(jdbc.queryForObject("select target_user_id from trade_reviews where writer_id=?", Long.class, BUYER)).isEqualTo(SELLER);
	}

	@ParameterizedTest
	@ValueSource(strings = {"{}", "{\"mannerScore\":0}", "{\"mannerScore\":6}", "{\"mannerScore\":null}"})
	void invalidScoreIs400(String body) throws Exception {
		long id = completed(SELLER, BUYER);
		mvc.perform(post(endpoint(id, "review")).header("Authorization", bearer(BUYER))
			.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
		assertThat(count("trade_reviews")).isZero();
	}

	@Test
	void longContentIsRejectedAndBlankContentIsNull() throws Exception {
		long id = completed(SELLER, BUYER);
		mvc.perform(post(endpoint(id, "review")).header("Authorization", bearer(BUYER))
			.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(java.util.Map.of("mannerScore", 5, "content", "a".repeat(501)))))
			.andExpect(status().isBadRequest());
		mvc.perform(post(endpoint(id, "review")).header("Authorization", bearer(BUYER))
			.contentType(MediaType.APPLICATION_JSON).content("{\"mannerScore\":5,\"content\":\"   \"}"))
			.andExpect(status().isOk());
		assertThat(jdbc.queryForObject("select content from trade_reviews", String.class)).isNull();
	}

	@Test
	void beforeCompletionIs409AndOutsiderIs404() throws Exception {
		long id = request(createPost(SELLER, PostType.SHARE), BUYER);
		mvc.perform(post(endpoint(id, "review")).header("Authorization", bearer(BUYER))
			.contentType(MediaType.APPLICATION_JSON).content("{\"mannerScore\":5}"))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INVALID_TRADE_STATUS"));
		mvc.perform(post(endpoint(id, "review")).header("Authorization", bearer(OTHER))
			.contentType(MediaType.APPLICATION_JSON).content("{\"mannerScore\":5}"))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("TRADE_NOT_FOUND"));
	}

	@Test
	void databaseUniqueConstraintHandlesConcurrentReviews() throws Exception {
		long id = completed(SELLER, BUYER);
		java.util.concurrent.Callable<String> write = () -> String.valueOf(mvc.perform(post(endpoint(id, "review"))
			.header("Authorization", bearer(BUYER)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"mannerScore\":5}")).andReturn().getResponse().getStatus());
		assertThat(race(write, write)).containsExactlyInAnyOrder("200", "409");
		assertThat(count("trade_reviews")).isEqualTo(1);
	}
}
