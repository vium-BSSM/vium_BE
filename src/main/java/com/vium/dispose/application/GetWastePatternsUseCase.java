package com.vium.dispose.application;

import com.vium.dispose.presentation.dto.GetWastePatternsResponse;
import com.vium.dispose.presentation.dto.WastePatternDto;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GetWastePatternsUseCase {

	private final JdbcTemplate jdbcTemplate;

	@Transactional(readOnly = true)
	public GetWastePatternsResponse execute(Long userId) {
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

		return new GetWastePatternsResponse(patterns);
	}
}
