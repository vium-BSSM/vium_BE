package com.vium.post.repository;

import com.vium.image.config.ImageStorageProperties;
import com.vium.post.dto.PostBasicInfo;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 다른 도메인(거래·채팅 등)이 게시물 기본 정보를 조회할 때 쓰는 읽기 전용 저장소.
 * 썸네일은 post_images의 sort_order=0(등록 시 첫 번째 이미지) 행을 uploaded_images와 조인해 만든다.
 */
@Repository
@RequiredArgsConstructor
public class PostQueryRepository {

	private static final short THUMBNAIL_SORT_ORDER = 0;

	private final JdbcTemplate jdbcTemplate;
	private final ImageStorageProperties imageStorageProperties;

	public Optional<PostBasicInfo> findBasicInfo(Long postId) {
		String sql = """
			select p.id, p.author_id, p.title, p.status, p.inventory_item_id, p.type, p.price,
			       img.object_key as thumbnail_object_key
			from posts p
			left join post_images pi on pi.post_id = p.id and pi.sort_order = ?
			left join uploaded_images img on img.id = pi.image_id
			where p.id = ? and p.deleted_at is null
			""";
		try {
			PostBasicInfo info = jdbcTemplate.queryForObject(sql, (rs, rowNum) -> new PostBasicInfo(
				rs.getLong("id"),
				rs.getLong("author_id"),
				rs.getString("title"),
				thumbnailUrl(rs.getString("thumbnail_object_key")),
				rs.getString("status"),
				rs.getLong("inventory_item_id"),
				rs.getString("type"),
				rs.getInt("price")
			), THUMBNAIL_SORT_ORDER, postId);
			return Optional.ofNullable(info);
		} catch (EmptyResultDataAccessException e) {
			return Optional.empty();
		}
	}

	private String thumbnailUrl(String objectKey) {
		if (objectKey == null) {
			return null;
		}
		return imageStorageProperties.publicBaseUrl().replaceAll("/$", "") + "/" + objectKey;
	}
}
