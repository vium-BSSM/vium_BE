package com.vium.report.repository;

import com.vium.report.dto.MonthlyWasteStats;
import com.vium.report.dto.MonthlyWasteStats.CategoryWaste;
import com.vium.report.dto.MonthlyWasteStats.ItemWaste;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * AI 리포트 생성을 위한 월간 소진·폐기 집계. consumption_events를 inventory_items/ingredient_catalog/
 * ingredient_categories와 조인하는 읽기 전용 조회이므로 여러 도메인의 테이블을 직접 조회한다(쓰기는 하지 않음).
 */
@Repository
@RequiredArgsConstructor
public class ReportStatsQueryRepository {

	private static final int TOP_ITEM_LIMIT = 10;
	private static final String UNCATEGORIZED = "미분류";
	private static final String UNNAMED_ITEM = "이름 없음";

	private final JdbcTemplate jdbcTemplate;

	public MonthlyWasteStats aggregate(Long userId, LocalDateTime start, LocalDateTime end) {
		long[] consumed = totalsFor(userId, "consumed", start, end);
		long[] disposed = totalsFor(userId, "disposed", start, end);
		List<CategoryWaste> categoryWaste = findCategoryWaste(userId, start, end);
		List<ItemWaste> itemWaste = findItemWaste(userId, start, end);
		Double avgDays = findAvgDaysPurchaseToDispose(userId, start, end);

		return new MonthlyWasteStats((int) consumed[0], (int) disposed[0], consumed[1], disposed[1],
			categoryWaste, itemWaste, avgDays);
	}

	private long[] totalsFor(Long userId, String eventType, LocalDateTime start, LocalDateTime end) {
		String sql = """
			SELECT COUNT(*) AS cnt, COALESCE(SUM(amount), 0) AS amt
			FROM consumption_events
			WHERE user_id = ? AND event_type = ?
			  AND occurred_at >= ? AND occurred_at < ?
			""";
		return jdbcTemplate.queryForObject(sql,
			(rs, rowNum) -> new long[] {rs.getLong("cnt"), rs.getLong("amt")},
			userId, eventType, Timestamp.valueOf(start), Timestamp.valueOf(end));
	}

	private List<CategoryWaste> findCategoryWaste(Long userId, LocalDateTime start, LocalDateTime end) {
		String sql = """
			SELECT COALESCE(ic.name, ?) AS category_name,
			       COUNT(*) AS waste_count,
			       COALESCE(SUM(ce.amount), 0) AS waste_amount
			FROM consumption_events ce
			JOIN inventory_items ii ON ii.id = ce.inventory_item_id
			LEFT JOIN ingredient_catalog cat ON cat.id = ii.ingredient_catalog_id
			LEFT JOIN ingredient_categories ic ON ic.id = cat.category_id
			WHERE ce.user_id = ? AND ce.event_type = 'disposed'
			  AND ce.occurred_at >= ? AND ce.occurred_at < ?
			GROUP BY COALESCE(ic.name, ?)
			ORDER BY waste_amount DESC, category_name
			""";
		return jdbcTemplate.query(sql, (rs, rowNum) -> new CategoryWaste(
				rs.getString("category_name"), rs.getInt("waste_count"), rs.getLong("waste_amount")),
			UNCATEGORIZED, userId, Timestamp.valueOf(start), Timestamp.valueOf(end), UNCATEGORIZED);
	}

	private List<ItemWaste> findItemWaste(Long userId, LocalDateTime start, LocalDateTime end) {
		String sql = """
			SELECT COALESCE(ii.custom_name, cat.name, ?) AS item_name,
			       COUNT(*) AS waste_count,
			       COALESCE(SUM(ce.amount), 0) AS waste_amount
			FROM consumption_events ce
			JOIN inventory_items ii ON ii.id = ce.inventory_item_id
			LEFT JOIN ingredient_catalog cat ON cat.id = ii.ingredient_catalog_id
			WHERE ce.user_id = ? AND ce.event_type = 'disposed'
			  AND ce.occurred_at >= ? AND ce.occurred_at < ?
			GROUP BY COALESCE(ii.custom_name, cat.name, ?)
			ORDER BY waste_amount DESC, item_name
			LIMIT ?
			""";
		return jdbcTemplate.query(sql, (rs, rowNum) -> new ItemWaste(
				rs.getString("item_name"), rs.getInt("waste_count"), rs.getLong("waste_amount")),
			UNNAMED_ITEM, userId, Timestamp.valueOf(start), Timestamp.valueOf(end), UNNAMED_ITEM, TOP_ITEM_LIMIT);
	}

	/** 구매일(purchased_on)이 있는 폐기 이벤트만 대상으로 한 평균 일수. 대상이 없으면 null. */
	private Double findAvgDaysPurchaseToDispose(Long userId, LocalDateTime start, LocalDateTime end) {
		String sql = """
			SELECT ii.purchased_on AS purchased_on, ce.occurred_at AS occurred_at
			FROM consumption_events ce
			JOIN inventory_items ii ON ii.id = ce.inventory_item_id
			WHERE ce.user_id = ? AND ce.event_type = 'disposed' AND ii.purchased_on IS NOT NULL
			  AND ce.occurred_at >= ? AND ce.occurred_at < ?
			""";
		List<Long> days = jdbcTemplate.query(sql, (rs, rowNum) -> {
			LocalDate purchasedOn = rs.getObject("purchased_on", LocalDate.class);
			LocalDateTime occurredAt = rs.getObject("occurred_at", LocalDateTime.class);
			return ChronoUnit.DAYS.between(purchasedOn, occurredAt.toLocalDate());
		}, userId, Timestamp.valueOf(start), Timestamp.valueOf(end));

		if (days.isEmpty()) {
			return null;
		}
		return days.stream().mapToLong(Long::longValue).average().orElse(0);
	}
}
