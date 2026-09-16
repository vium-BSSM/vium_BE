package com.vium.dispose.repository;

import com.vium.dispose.dto.WastePatternDto;
import com.vium.dispose.dto.WasteReportDto;
import java.time.LocalDate;
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
			select row_number() over (order by period_start desc) as waste_report_id,
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
