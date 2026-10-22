package com.vium.dispose.repository;

import com.vium.dispose.dto.TopWastedItemDto;
import com.vium.dispose.dto.WastePatternDto;
import com.vium.dispose.dto.WasteReportDto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class WasteReportQueryRepository {

	private final JdbcTemplate jdbcTemplate;

	public List<WasteReportDto> findReports(Long userId) {
		String sql = """
			select extract(year from period_start)::bigint * 100 + extract(month from period_start)::bigint as waste_report_id,
			       period_start,
			       period_end,
			       total_wasted_amount,
			       0::bigint as total_saved_amount
			from (
			  select date_trunc('month', ce.occurred_at)::date as period_start,
			         (date_trunc('month', ce.occurred_at) + interval '1 month' - interval '1 day')::date as period_end,
			         coalesce(sum(ce.amount), 0)::bigint as total_wasted_amount
			  from consumption_events ce
			  where ce.user_id = ? and ce.event_type = 'disposed'
			  group by date_trunc('month', ce.occurred_at)
			) sub
			order by period_start desc
			""";

		List<WasteReportDto> reports = jdbcTemplate.query(sql, (rs, rowNum) -> new WasteReportDto(
			rs.getLong("waste_report_id"),
			rs.getObject("period_start", LocalDate.class),
			rs.getObject("period_end", LocalDate.class),
			rs.getLong("total_wasted_amount"),
			rs.getLong("total_saved_amount")), userId);

		return reports;
	}

	/** 구간 [start, end) 에서 결과가 난 재료 수: [등록(처리) 수, 폐기 재료 수]. 섭취 수 = 등록 - 폐기. */
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

	/** 구간 [start, end) 의 폐기 금액 합계 상위 3개 재료. */
	public List<TopWastedItemDto> findTopWastedItems(Long userId, LocalDateTime start, LocalDateTime end) {
		String sql = """
			select ce.inventory_item_id as item_id,
			       coalesce(ii.custom_name, ingr.name) as name,
			       sum(ce.amount) as loss_amount,
			       ii.amount as purchase_amount
			from consumption_events ce
			join inventory_items ii on ii.id = ce.inventory_item_id
			left join ingredient_catalog ingr on ingr.id = ii.ingredient_catalog_id
			where ce.user_id = ? and ce.event_type = 'disposed' and ce.amount is not null
			  and ce.occurred_at >= ? and ce.occurred_at < ?
			group by ce.inventory_item_id, coalesce(ii.custom_name, ingr.name), ii.amount
			having sum(ce.amount) > 0
			order by sum(ce.amount) desc, ce.inventory_item_id
			limit 3
			""";
		int[] rank = {0};
		return jdbcTemplate.query(sql, (rs, rowNum) -> {
			BigDecimal loss = rs.getBigDecimal("loss_amount");
			BigDecimal purchase = rs.getBigDecimal("purchase_amount");
			BigDecimal ratio = null;
			if (purchase != null && purchase.signum() > 0) {
				ratio = loss.divide(purchase, 4, RoundingMode.HALF_UP);
			}
			return new TopWastedItemDto(++rank[0], rs.getString("name"), loss.longValue(),
				purchase == null ? null : purchase.longValue(), ratio);
		}, userId, Timestamp.valueOf(start), Timestamp.valueOf(end));
	}

	public List<WastePatternDto> findPatterns(Long userId) {
		String sql = """
			select ic.id as category_id,
			       ic.name as category_name,
			       count(ce.id) as waste_count,
			       coalesce(sum(ce.amount), 0)::numeric as total_wasted_amount,
			       coalesce(sum(ce.amount), 0)::numeric /
			       nullif((select coalesce(sum(amount), 0)::numeric from consumption_events
			                where user_id = ? and event_type = 'disposed'), 0) as waste_ratio
			from consumption_events ce
			join inventory_items ii on ce.inventory_item_id = ii.id
			left join ingredient_catalog ingr on ingr.id = ii.ingredient_catalog_id
			left join ingredient_categories ic on ic.id = ingr.category_id
			where ce.user_id = ? and ce.event_type = 'disposed'
			group by ic.id, ic.name
			order by waste_ratio desc nulls last
			""";

		List<WastePatternDto> patterns = jdbcTemplate.query(sql, (rs, rowNum) -> {
			Long catId = rs.getObject("category_id") != null ? rs.getLong("category_id") : null;
			String catName = rs.getString("category_name");
			return new WastePatternDto(
				catId,
				catName,
				rs.getLong("waste_count"),
				rs.getBigDecimal("total_wasted_amount").longValue(),
				rs.getBigDecimal("waste_ratio").doubleValue()
			);
		}, userId, userId);

		return patterns;
	}
}
