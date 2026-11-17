package com.vium.dashboard.repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class DashboardQueryRepository {

	private final JdbcTemplate jdbcTemplate;

	/** 구간 [start, end) 에서 결과가 난 재료 수: [등록(처리) 수, 폐기 재료 수]. waste-reports의 countItems와 동일한 정의. */
	public int[] countItems(Long userId, LocalDateTime start, LocalDateTime end) {
		String sql = """
			select count(distinct ce.inventory_item_id) as registered_count,
			       count(distinct case when ce.event_type = 'disposed' then ce.inventory_item_id end) as expired_count
			from consumption_events ce
			where ce.user_id = ?
			  and ce.event_type in ('consumed', 'disposed')
			  and ce.occurred_at >= ? and ce.occurred_at < ?
			""";
		return jdbcTemplate.queryForObject(sql,
			(rs, rowNum) -> new int[] {rs.getInt("registered_count"), rs.getInt("expired_count")},
			userId, Timestamp.valueOf(start), Timestamp.valueOf(end));
	}

	/** 구간 [start, end) 의 eventType 이벤트 amount 합. null 제외, 없으면 0. */
	public long sumAmountByEventTypeInRange(Long userId, String eventType, LocalDateTime start, LocalDateTime end) {
		String sql = """
			select coalesce(sum(amount), 0) as total
			from consumption_events
			where user_id = ? and event_type = ? and amount is not null
			  and occurred_at >= ? and occurred_at < ?
			""";
		BigDecimal total = jdbcTemplate.queryForObject(sql, (rs, rowNum) -> rs.getBigDecimal("total"),
			userId, eventType, Timestamp.valueOf(start), Timestamp.valueOf(end));
		return total.longValue();
	}

	/** 전체 기간의 eventType 이벤트 amount 합. null 제외, 없으면 0. */
	public long sumAmountByEventType(Long userId, String eventType) {
		String sql = """
			select coalesce(sum(amount), 0) as total
			from consumption_events
			where user_id = ? and event_type = ? and amount is not null
			""";
		BigDecimal total = jdbcTemplate.queryForObject(sql, (rs, rowNum) -> rs.getBigDecimal("total"),
			userId, eventType);
		return total.longValue();
	}

	/** 구간 [start, end) 에서 eventType 이벤트가 있는 서로 다른 재고 수. */
	public int countDistinctItemsByEventTypeInRange(Long userId, String eventType, LocalDateTime start,
			LocalDateTime end) {
		String sql = """
			select count(distinct inventory_item_id) as cnt
			from consumption_events
			where user_id = ? and event_type = ?
			  and occurred_at >= ? and occurred_at < ?
			""";
		return jdbcTemplate.queryForObject(sql, Integer.class,
			userId, eventType, Timestamp.valueOf(start), Timestamp.valueOf(end));
	}

	/** 마지막 disposed 이벤트 시각(occurred_at, Asia/Seoul 기준 naive). event_source 필터 없음(자동 폐기 포함). */
	public Optional<LocalDateTime> findLastDisposedAt(Long userId) {
		String sql = """
			select max(occurred_at) as last_at
			from consumption_events
			where user_id = ? and event_type = 'disposed'
			""";
		LocalDateTime lastAt = jdbcTemplate.queryForObject(sql,
			(rs, rowNum) -> rs.getObject("last_at", LocalDateTime.class), userId);
		return Optional.ofNullable(lastAt);
	}

	public int countActiveIngredients(Long userId) {
		String sql = """
			select count(*) as cnt
			from inventory_items ii
			join item_statuses s on s.id = ii.status_id
			where ii.user_id = ? and s.code = 'active'
			""";
		return jdbcTemplate.queryForObject(sql, Integer.class, userId);
	}

	/** expiringSoon 정의: status=active and expires_on <= expiresThrough (GET /api/me/ingredients?expiringSoon=true 와 동일). */
	public int countExpiringSoon(Long userId, LocalDate expiresThrough) {
		String sql = """
			select count(*) as cnt
			from inventory_items ii
			join item_statuses s on s.id = ii.status_id
			where ii.user_id = ? and s.code = 'active' and ii.expires_on <= ?
			""";
		return jdbcTemplate.queryForObject(sql, Integer.class, userId, expiresThrough);
	}
}
