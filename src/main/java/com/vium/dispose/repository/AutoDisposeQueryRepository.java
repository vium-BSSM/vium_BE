package com.vium.dispose.repository;

import com.vium.inventory.dto.AutoDisposeCandidate;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AutoDisposeQueryRepository {

	private final JdbcTemplate jdbcTemplate;

	public List<AutoDisposeCandidate> findCandidates(Short activeStatusId, LocalDate today) {
		String sql = """
			select i.id, i.user_id, i.remaining_quantity, i.initial_quantity, i.amount, i.expires_on
			from inventory_items i
			join users u on u.id = i.user_id
			where i.status_id = ? and i.expires_on < ? and i.remaining_quantity > 0
			  and u.auto_dispose_enabled = true
			  and i.keep_confirmed_expires_on is distinct from i.expires_on
			order by i.id
			""";
		return jdbcTemplate.query(sql, (rs, rowNum) -> new AutoDisposeCandidate(
			rs.getLong("id"), rs.getLong("user_id"), rs.getBigDecimal("remaining_quantity"),
			rs.getBigDecimal("initial_quantity"), rs.getBigDecimal("amount"),
			rs.getObject("expires_on", LocalDate.class)
		), activeStatusId, today);
	}
}
