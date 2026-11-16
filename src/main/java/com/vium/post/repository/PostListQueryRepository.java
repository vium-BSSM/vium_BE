package com.vium.post.repository;

import com.vium.image.config.ImageStorageProperties;
import com.vium.post.dto.PostCursor;
import com.vium.post.dto.PostListItem;
import com.vium.post.dto.PostSort;
import com.vium.post.entity.PostType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 게시물 목록 조회. PostGIS 없이 Haversine으로 거리를 계산한다.
 * 먼저 (latitude, longitude) 바운딩 박스로 거르고(인덱스 활용), 바깥 쿼리에서 정확한 거리로
 * 다시 필터·정렬한다. 거리는 미터 정수로 반올림해 커서 비교와 응답에 그대로 쓴다.
 */
@Repository
@RequiredArgsConstructor
public class PostListQueryRepository {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	// 위도 1도 ≈ 111km. 바운딩 박스는 실제 반경보다 넉넉하게 잡기 위해 110km로 나눈다(보수적 과대 추정).
	private static final double METERS_PER_DEGREE_LATITUDE = 110_000.0;
	private static final double MIN_COS_LATITUDE = 0.1;

	private final JdbcTemplate jdbcTemplate;
	private final ImageStorageProperties imageStorageProperties;

	public List<PostListItem> findPosts(Long viewerId, double latitude, double longitude, int distanceMeters,
			PostSort sort, PostType type, Long ingredientCatalogId, Set<Long> excludedAuthorIds, LocalDate today,
			PostCursor cursor, int limit) {
		double latDelta = distanceMeters / METERS_PER_DEGREE_LATITUDE;
		double cosLatitude = Math.max(Math.cos(Math.toRadians(latitude)), MIN_COS_LATITUDE);
		double lngDelta = distanceMeters / (METERS_PER_DEGREE_LATITUDE * cosLatitude);

		StringBuilder sql = new StringBuilder("""
			select * from (
			  select p.id as post_id, p.type, p.status, p.title, p.price, p.like_count, p.created_at,
			         coalesce(c.name, i.custom_name) as ingredient_name,
			         i.remaining_quantity as quantity, u.name as unit, i.expires_on,
			         img.object_key as thumbnail_object_key,
			         round(2 * 6371000 * asin(sqrt(
			             power(sin(radians(? - p.latitude) / 2), 2) +
			             cos(radians(?)) * cos(radians(p.latitude)) *
			             power(sin(radians(? - p.longitude) / 2), 2)
			         ))) as distance_meters,
			         case when pl.user_id is not null then true else false end as liked_by_me
			  from posts p
			  join inventory_items i on i.id = p.inventory_item_id
			  left join ingredient_catalog c on c.id = i.ingredient_catalog_id
			  join units u on u.id = i.unit_id
			  left join post_images pi on pi.post_id = p.id and pi.sort_order = 0
			  left join uploaded_images img on img.id = pi.image_id
			  left join post_likes pl on pl.post_id = p.id and pl.user_id = ?
			  where p.status in ('OPEN', 'RESERVED')
			    and p.deleted_at is null
			    and p.latitude between ? and ?
			    and p.longitude between ? and ?
			    and i.expires_on >= ?
			""");

		List<Object> params = new ArrayList<>();
		params.add(latitude);
		params.add(latitude);
		params.add(longitude);
		params.add(viewerId);
		params.add(latitude - latDelta);
		params.add(latitude + latDelta);
		params.add(longitude - lngDelta);
		params.add(longitude + lngDelta);
		params.add(today);

		if (type != null) {
			sql.append("    and p.type = ?\n");
			params.add(type.name());
		}
		if (ingredientCatalogId != null) {
			sql.append("    and i.ingredient_catalog_id = ?\n");
			params.add(ingredientCatalogId);
		}
		if (!excludedAuthorIds.isEmpty()) {
			sql.append("    and p.author_id not in (")
				.append(String.join(",", Collections.nCopies(excludedAuthorIds.size(), "?")))
				.append(")\n");
			params.addAll(excludedAuthorIds);
		}

		sql.append(") t where t.distance_meters <= ?\n");
		params.add(distanceMeters);

		if (cursor != null) {
			if (sort == PostSort.DISTANCE) {
				sql.append("  and (t.distance_meters > ? or (t.distance_meters = ? and t.post_id > ?))\n");
				params.add(cursor.distanceMeters());
				params.add(cursor.distanceMeters());
				params.add(cursor.postId());
			} else {
				Timestamp cursorCreatedAt = Timestamp.valueOf(
					cursor.createdAt().atZoneSameInstant(SEOUL).toLocalDateTime());
				sql.append("  and (t.created_at < ? or (t.created_at = ? and t.post_id < ?))\n");
				params.add(cursorCreatedAt);
				params.add(cursorCreatedAt);
				params.add(cursor.postId());
			}
		}

		if (sort == PostSort.DISTANCE) {
			sql.append("order by t.distance_meters asc, t.post_id asc\n");
		} else {
			sql.append("order by t.created_at desc, t.post_id desc\n");
		}
		sql.append("limit ?");
		params.add(limit);

		return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> mapRow(rs), params.toArray());
	}

	private PostListItem mapRow(ResultSet rs) throws SQLException {
		LocalDateTime createdAt = rs.getObject("created_at", LocalDateTime.class);
		return new PostListItem(
			rs.getLong("post_id"),
			rs.getString("type"),
			rs.getString("status"),
			rs.getString("title"),
			imageUrl(rs.getString("thumbnail_object_key")),
			rs.getString("ingredient_name"),
			rs.getBigDecimal("quantity"),
			rs.getString("unit"),
			rs.getObject("expires_on", LocalDate.class),
			rs.getInt("price"),
			rs.getInt("distance_meters"),
			rs.getInt("like_count"),
			rs.getBoolean("liked_by_me"),
			createdAt.atZone(SEOUL).toOffsetDateTime()
		);
	}

	private String imageUrl(String objectKey) {
		if (objectKey == null) {
			return null;
		}
		return imageStorageProperties.publicBaseUrl().replaceAll("/$", "") + "/" + objectKey;
	}
}
