package com.vium.post.repository;

import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 게시물 만료 배치가 쓰는 읽기 전용 조회. OPEN 게시물의 재고(inventory_items)를 조인해
 * 소비기한이 지난 게시물 ID만 뽑는다. 실제 상태 변경은 PostStateService가 한다.
 */
@Repository
@RequiredArgsConstructor
public class PostExpiryQueryRepository {

	private final JdbcTemplate jdbcTemplate;

	public List<Long> findOpenPostIdsWithExpiredIngredient(LocalDate today) {
		String sql = """
			select p.id
			from posts p
			join inventory_items i on i.id = p.inventory_item_id
			where p.status = 'OPEN' and p.deleted_at is null and i.expires_on < ?
			""";
		return jdbcTemplate.queryForList(sql, Long.class, today);
	}
}
