package com.vium.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.image.entity.ImagePurpose;
import com.vium.image.entity.UploadedImage;
import com.vium.image.repository.UploadedImageRepository;
import com.vium.image.service.ImageService;
import com.vium.post.entity.PostImage;
import com.vium.post.entity.PostType;
import com.vium.post.repository.PostImageRepository;
import com.vium.trade.dto.ReviewCreateRequest;
import com.vium.trade.dto.TradeCursor;
import com.vium.trade.service.TradeCursorCodec;
import com.vium.trade.service.TradeQueryService;
import com.vium.trade.service.TradeReviewService;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

class TradeQueryIntegrationTest extends TradeIntegrationSupport {
	@Autowired TradeQueryService queries;
	@Autowired TradeReviewService reviews;
	@Autowired TradeCursorCodec codec;
	@Autowired UploadedImageRepository images;
	@Autowired PostImageRepository postImages;
	@Autowired ImageService imageService;

	@Test
	void roleAndMultipleStatusesFilterOnlyOwnTrades() throws Exception {
		long requested = request(createPost(SELLER, PostType.SALE), BUYER);
		long accepted = accepted(createPost(SELLER, PostType.SHARE), BUYER);
		accepted(createPost(BUYER, PostType.SHARE), OTHER);
		request(createPost(SELLER, PostType.SHARE), OTHER);
		mvc.perform(get("/api/me/trades").param("role", "BUYER").param("status", "REQUESTED,ACCEPTED")
			.header("Authorization", bearer(BUYER))).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.trades.length()").value(2))
			.andExpect(jsonPath("$.data.trades[0].tradeId").value(accepted))
			.andExpect(jsonPath("$.data.trades[0].autoResolveAt").value("2026-11-20T01:00:00+09:00"))
			.andExpect(jsonPath("$.data.trades[1].tradeId").value(requested))
			.andExpect(jsonPath("$.data.trades[1].autoResolveAt").isEmpty());
		mvc.perform(get("/api/me/trades").param("role", "SELLER").header("Authorization", bearer(BUYER)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.trades.length()").value(1));
		assertThat(queries.list(BUYER, null, null, null, 20).trades()).hasSize(3);
	}

	@Test
	void sameTimestampCursorHasNoDuplicatesAndDeletedPostsRemainVisible() {
		long first = request(createPost(SELLER, PostType.SHARE), BUYER);
		var deleted = createPost(SELLER, PostType.SHARE);
		long second = request(deleted, BUYER);
		trades.reject(SELLER, second);
		jdbc.update("update posts set deleted_at=CURRENT_TIMESTAMP where id=?", deleted.getId());
		long third = request(createPost(SELLER, PostType.SHARE), BUYER);
		var page1 = queries.list(BUYER, null, null, null, 2);
		assertThat(page1.trades()).extracting(t -> t.tradeId()).containsExactly(third, second);
		assertThat(page1.hasNext()).isTrue();
		var page2 = queries.list(BUYER, null, null, page1.nextCursor(), 2);
		assertThat(page2.trades()).extracting(t -> t.tradeId()).containsExactly(first);
		assertThat(page2.hasNext()).isFalse();
		assertThat(page2.nextCursor()).isNull();
		var decoded = codec.decode(page1.nextCursor());
		var utcCursor = codec.encode(new TradeCursor(decoded.requestedAt().withOffsetSameInstant(java.time.ZoneOffset.UTC), decoded.tradeId()));
		assertThat(queries.list(BUYER, null, null, utcCursor, 2)).isEqualTo(page2);
	}

	@Test
	void joinsImagesPartnerAndReviewAndHidesCompletedDeadline() throws Exception {
		var post = createPost(SELLER, PostType.SALE);
		var image = UploadedImage.pending(SELLER, ImagePurpose.PROFILE, "bucket", "profile/a.png", "image/png", 10, NOW);
		image.markReady();
		image = images.saveAndFlush(image);
		jdbc.update("update users set profile_image_id=? where id=?", image.getId(), SELLER);
		postImages.saveAndFlush(PostImage.builder().postId(post.getId()).imageId(image.getId()).sortOrder((short) 0).build());
		long id = accepted(post, BUYER);
		trades.complete(BUYER, id);
		reviews.create(BUYER, id, new ReviewCreateRequest(5, null));
		var summary = queries.list(BUYER, null, null, null, 20).trades().getFirst();
		assertThat(summary.partner().profileImageUrl()).isEqualTo(imageService.findProfileImageUrl(image.getId()));
		assertThat(summary.post().thumbnailUrl()).isEqualTo("https://cdn.example.test/profile/a.png");
		assertThat(summary.partner().userId()).isEqualTo(SELLER);
		assertThat(summary.autoResolveAt()).isNull();
		assertThat(summary.reviewWritten()).isTrue();
		assertThat(queries.list(SELLER, null, null, null, 20).trades().getFirst().reviewWritten()).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = {"not-base64!", "", "e30", "bnVsbA"})
	void invalidCursorIs400(String cursor) throws Exception {
		mvc.perform(get("/api/me/trades").param("cursor", cursor).header("Authorization", bearer(BUYER)))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"0", "-1", "1.5", "\"1\"", "9223372036854775808"})
	void malformedCursorIdIs400(String id) throws Exception {
		String raw = "{\"requestedAt\":\"2026-11-17T01:00:00+09:00\",\"tradeId\":" + id + "}";
		String cursor = Base64.getUrlEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
		mvc.perform(get("/api/me/trades").param("cursor", cursor).header("Authorization", bearer(BUYER)))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	void invalidEnumAndSizeAre400() throws Exception {
		for (String[] query : new String[][]{{"role", "INVALID"}, {"status", "ACCEPTED,INVALID"}, {"size", "0"}, {"size", "51"}}) {
			mvc.perform(get("/api/me/trades").param(query[0], query[1]).header("Authorization", bearer(BUYER)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
		}
	}
}
