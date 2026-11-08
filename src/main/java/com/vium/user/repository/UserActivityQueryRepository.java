package com.vium.user.repository;

import com.vium.user.dto.UserActivityCounts;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class UserActivityQueryRepository {
	private final JdbcTemplate jdbcTemplate;

	/** 시작 경계는 DB 시각과 같은 UTC 벽시계 값이다. */
	public UserActivityCounts count(LocalDateTime todayStart, LocalDateTime weekStart) {
		return jdbcTemplate.queryForObject("""
			select
			  (select count(distinct user_id) from user_sessions where issued_at >= ?) as active_today,
			  (select count(distinct user_id) from user_sessions where issued_at >= ?) as active_7d,
			  (select count(*) from users where deleted_at is null) as registered,
			  (select count(*) from users where deleted_at is null and created_at >= ?) as signups_today
			""", (rs, rowNum) -> new UserActivityCounts(rs.getLong("active_today"), rs.getLong("active_7d"),
				rs.getLong("registered"), rs.getLong("signups_today")),
			Timestamp.valueOf(todayStart), Timestamp.valueOf(weekStart), Timestamp.valueOf(todayStart));
	}
}
