package com.vium.safety.repository;

import com.vium.safety.dto.BlockedUserRow;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * posts·users 테이블을 읽기 전용으로 조회한다. posts JPA 엔티티가 아직 없어 safety 도메인에서 직접 조회한다.
 */
@Repository
@RequiredArgsConstructor
public class SafetyQueryRepository {

	private final JdbcTemplate jdbcTemplate;

	public Optional<Long> findPostAuthorId(Long postId) {
		return jdbcTemplate.query(
			"select author_id from posts where id = ? and deleted_at is null",
			(rs, rowNum) -> rs.getLong("author_id"), postId).stream().findFirst();
	}

	public List<BlockedUserRow> findBlockedUsers(Long blockerId) {
		return jdbcTemplate.query("""
			select u.id as user_id, u.display_name as display_name, ub.created_at as blocked_at
			from user_blocks ub
			join users u on u.id = ub.blocked_id
			where ub.blocker_id = ?
			  and u.deleted_at is null
			order by ub.created_at desc
			""", (rs, rowNum) -> new BlockedUserRow(rs.getLong("user_id"), rs.getString("display_name"),
				rs.getObject("blocked_at", LocalDateTime.class)), blockerId);
	}
}
