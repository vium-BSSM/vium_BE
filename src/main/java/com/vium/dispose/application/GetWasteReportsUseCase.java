package com.vium.dispose.application;

import com.vium.dispose.presentation.dto.GetWasteReportsResponse;
import com.vium.dispose.presentation.dto.WasteReportDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GetWasteReportsUseCase {

	private final JdbcTemplate jdbcTemplate;

	@Transactional(readOnly = true)
	public GetWasteReportsResponse execute(Long userId) {
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

		return new GetWasteReportsResponse(reports);
	}
}
