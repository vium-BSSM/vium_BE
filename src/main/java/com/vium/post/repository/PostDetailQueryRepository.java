package com.vium.post.repository;

import com.vium.image.config.ImageStorageProperties;
import com.vium.post.dto.MannerStats;
import com.vium.post.dto.PostImageItem;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 게시물 상세 응답을 만들 때 필요한, post 도메인 밖(uploaded_images, trades, trade_reviews)을
 * 읽기 전용으로 조인하는 저장소. W2 이슈가 명시한 "읽기 전용 참조" 범위 안에서만 쓴다.
 */
@Repository
@RequiredArgsConstructor
public class PostDetailQueryRepository {

	private final JdbcTemplate jdbcTemplate;
	private final ImageStorageProperties imageStorageProperties;

	public List<PostImageItem> findImages(Long postId) {
		String sql = """
			select pi.image_id, img.object_key
			from post_images pi
			join uploaded_images img on img.id = pi.image_id
			where pi.post_id = ?
			order by pi.sort_order asc
			""";
		return jdbcTemplate.query(sql, (rs, rowNum) -> new PostImageItem(
			rs.getLong("image_id"),
			imageUrl(rs.getString("object_key"))
		), postId);
	}

	public MannerStats findMannerStats(Long targetUserId) {
		String sql = """
			select avg(manner_score) as avg_score, count(*) as review_count
			from trade_reviews
			where target_user_id = ?
			""";
		try {
			return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> {
				BigDecimal avgScore = rs.getBigDecimal("avg_score");
				long reviewCount = rs.getLong("review_count");
				BigDecimal mannerScore = avgScore == null ? null : avgScore.setScale(1, RoundingMode.HALF_UP);
				return new MannerStats(mannerScore, reviewCount);
			}, targetUserId);
		} catch (EmptyResultDataAccessException e) {
			return MannerStats.EMPTY;
		}
	}

	public Long findActiveTradeId(Long postId, Long userId) {
		String sql = """
			select id from trades
			where post_id = ? and status in ('REQUESTED', 'ACCEPTED') and (buyer_id = ? or seller_id = ?)
			order by requested_at desc
			limit 1
			""";
		List<Long> ids = jdbcTemplate.queryForList(sql, Long.class, postId, userId, userId);
		return ids.isEmpty() ? null : ids.get(0);
	}

	private String imageUrl(String objectKey) {
		if (objectKey == null) {
			return null;
		}
		return imageStorageProperties.publicBaseUrl().replaceAll("/$", "") + "/" + objectKey;
	}
}
