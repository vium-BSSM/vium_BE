package com.vium.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.post.entity.PostType;
import com.vium.post.service.PostDetailService;
import com.vium.trade.dto.ReviewCreateRequest;
import com.vium.trade.service.TradeReviewService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

class UserReviewsIntegrationTest extends TradeIntegrationSupport {
	@Autowired TradeReviewService reviews;
	@Autowired PostDetailService postDetails;

	@Test
	void scoreIncludesNoContentAndMatchesPostDetailWithCursorPagination() throws Exception {
		var visible = createPost(SELLER, PostType.SHARE);
		reviews.create(BUYER, completed(SELLER, BUYER), new ReviewCreateRequest(5, "첫 후기"));
		reviews.create(OTHER, completed(SELLER, OTHER), new ReviewCreateRequest(4, null));
		var last = reviews.create(45L, completed(SELLER, 45L), new ReviewCreateRequest(4, "마지막 후기"));
		jdbc.update("update users set deleted_at=CURRENT_TIMESTAMP where id=45");
		var result = mvc.perform(get("/api/users/" + SELLER + "/reviews").param("size", "1").header("Authorization", bearer(BUYER)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.mannerScore").value(4.3))
			.andExpect(jsonPath("$.data.reviewCount").value(3)).andExpect(jsonPath("$.data.reviews.length()").value(1))
			.andExpect(jsonPath("$.data.reviews[0].reviewId").value(last.reviewId()))
			.andExpect(jsonPath("$.data.reviews[0].writer.displayName").value("User45"))
			.andExpect(jsonPath("$.data.hasNext").value(true)).andReturn();
		String cursor = mapper.readTree(result.getResponse().getContentAsString()).get("data").get("nextCursor").asString();
		mvc.perform(get("/api/users/" + SELLER + "/reviews").param("size", "1").param("cursor", cursor).header("Authorization", bearer(BUYER)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.reviews[0].content").value("첫 후기"))
			.andExpect(jsonPath("$.data.hasNext").value(false)).andExpect(jsonPath("$.data.nextCursor").isEmpty());
		assertThat(reviews.list(SELLER, null, 20).mannerScore()).isEqualByComparingTo(postDetails.getDetail(BUYER, visible.getId(), null, null).author().mannerScore());
	}

	@Test
	void emptyStatsAndMissingOrDeletedUser() throws Exception {
		mvc.perform(get("/api/users/" + SELLER + "/reviews").header("Authorization", bearer(BUYER)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.mannerScore").isEmpty())
			.andExpect(jsonPath("$.data.reviewCount").value(0)).andExpect(jsonPath("$.data.reviews").isEmpty());
		jdbc.update("update users set deleted_at=CURRENT_TIMESTAMP where id=?", SELLER);
		for (long user : new long[]{SELLER, 999}) {
			mvc.perform(get("/api/users/" + user + "/reviews").header("Authorization", bearer(BUYER)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"abc", "0", "-1", "999999999999999999999999999", ""})
	void invalidCursorIs400(String cursor) throws Exception {
		mvc.perform(get("/api/users/" + SELLER + "/reviews").param("cursor", cursor).header("Authorization", bearer(BUYER)))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 51})
	void invalidSizeIs400(int size) throws Exception {
		mvc.perform(get("/api/users/" + SELLER + "/reviews").param("size", Integer.toString(size)).header("Authorization", bearer(BUYER)))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}
}
