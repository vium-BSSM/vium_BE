package com.vium.trade.repository;

import com.vium.image.config.ImageStorageProperties;
import com.vium.post.entity.PostType;
import com.vium.trade.dto.ReviewItem;
import com.vium.trade.dto.TradeCursor;
import com.vium.trade.dto.TradeRole;
import com.vium.trade.dto.TradeSummary;
import com.vium.trade.entity.TradeStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class TradeQueryRepository {
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	private final JdbcTemplate jdbcTemplate;
	private final ImageStorageProperties imageStorageProperties;

	public List<TradeSummary> findTrades(Long me, TradeRole role, List<TradeStatus> statuses,
			TradeCursor cursor, int limit) {
		StringBuilder sql = new StringBuilder("""
			select t.id, t.status, t.price, t.requested_at, t.accepted_at, t.completed_at, t.auto_resolve_at,
			case when t.buyer_id = ? then 'BUYER' else 'SELLER' end as role,
			p.id as post_id, p.type, p.title, thumb.object_key as thumb_key,
			u.id as partner_id, u.display_name, pimg.object_key as profile_key,
			exists (select 1 from trade_reviews r where r.trade_id = t.id and r.writer_id = ?) as review_written
			from trades t join posts p on p.id = t.post_id
			left join post_images pi on pi.post_id = p.id and pi.sort_order = 0
			left join uploaded_images thumb on thumb.id = pi.image_id
			join users u on u.id = case when t.buyer_id = ? then t.seller_id else t.buyer_id end
			left join uploaded_images pimg on pimg.id = u.profile_image_id
			where (t.buyer_id = ? or t.seller_id = ?)
			""");
		List<Object> args = new ArrayList<>(List.of(me, me, me, me, me));
		if (role != null) {
			sql.append(role == TradeRole.BUYER ? " and t.buyer_id = ?" : " and t.seller_id = ?");
			args.add(me);
		}
		if (statuses != null && !statuses.isEmpty()) {
			sql.append(" and t.status in (").append(String.join(",", Collections.nCopies(statuses.size(), "?"))).append(")");
			statuses.forEach(status -> args.add(status.name()));
		}
		if (cursor != null) {
			sql.append(" and (t.requested_at < ? or (t.requested_at = ? and t.id < ?))");
			var requestedAt = cursor.requestedAt().atZoneSameInstant(SEOUL).toLocalDateTime();
			args.add(requestedAt);
			args.add(requestedAt);
			args.add(cursor.tradeId());
		}
		sql.append(" order by t.requested_at desc, t.id desc limit ?");
		args.add(limit);
		return jdbcTemplate.query(sql.toString(), (rs, row) -> {
			TradeStatus status = TradeStatus.valueOf(rs.getString("status"));
			return new TradeSummary(rs.getLong("id"), status, TradeRole.valueOf(rs.getString("role")),
				new TradeSummary.PostInfo(rs.getLong("post_id"), PostType.valueOf(rs.getString("type")),
					rs.getString("title"), imageUrl(rs.getString("thumb_key"))),
				new TradeSummary.Partner(rs.getLong("partner_id"), rs.getString("display_name"), imageUrl(rs.getString("profile_key"))),
				rs.getInt("price"), time(rs, "requested_at"), time(rs, "accepted_at"), time(rs, "completed_at"),
				status == TradeStatus.ACCEPTED ? time(rs, "auto_resolve_at") : null, rs.getBoolean("review_written"));
		}, args.toArray());
	}

	public ReviewStats findReviewStats(Long userId) {
		return jdbcTemplate.queryForObject("""
			select avg(manner_score) as score, count(*) as total from trade_reviews where target_user_id = ?
			""", (rs, row) -> {
			BigDecimal score = rs.getBigDecimal("score");
			return new ReviewStats(score == null ? null : score.setScale(1, RoundingMode.HALF_UP), rs.getLong("total"));
		}, userId);
	}

	public List<ReviewItem> findReviews(Long userId, Long cursor, int limit) {
		String sql = """
			select r.id, r.writer_id, u.display_name, r.manner_score, r.content, r.created_at
			from trade_reviews r join users u on u.id = r.writer_id
			where r.target_user_id = ? and r.content is not null
			""";
		List<Object> args = new ArrayList<>(List.of(userId));
		if (cursor != null) { sql += " and r.id < ?"; args.add(cursor); }
		sql += " order by r.id desc limit ?";
		args.add(limit);
		return jdbcTemplate.query(sql, (rs, row) -> new ReviewItem(rs.getLong("id"),
			new ReviewItem.Writer(rs.getLong("writer_id"), rs.getString("display_name")),
			rs.getInt("manner_score"), rs.getString("content"), time(rs, "created_at")), args.toArray());
	}

	private OffsetDateTime time(ResultSet rs, String column) throws SQLException {
		var value = rs.getTimestamp(column);
		return value == null ? null : value.toLocalDateTime().atZone(SEOUL).toOffsetDateTime();
	}

	private String imageUrl(String key) {
		return key == null ? null : imageStorageProperties.publicBaseUrl().replaceAll("/$", "") + "/" + key;
	}

	public record ReviewStats(BigDecimal mannerScore, long reviewCount) {}
}
