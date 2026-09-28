package com.vium.dispose.repository;

import com.vium.dispose.dto.ConsumptionEventDto;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ConsumptionEventQueryRepository {

	private final JdbcTemplate jdbcTemplate;

	public List<ConsumptionEventDto> findEvents(Long userId, LocalDate from, LocalDate to, String eventType) {
		LocalDateTime fromDateTime = from != null ? from.atStartOfDay() : null;
		LocalDateTime toDateTime = to != null ? to.atTime(LocalTime.MAX) : null;

		StringBuilder sql = new StringBuilder("""
			SELECT id, inventory_item_id, event_type, event_source_id, quantity, amount, occurred_at
			FROM consumption_events
			WHERE user_id = ?
			""");

		List<Object> params = new ArrayList<>();
		params.add(userId);

		if (fromDateTime != null) {
			sql.append("AND occurred_at >= ? ");
			params.add(Timestamp.valueOf(fromDateTime));
		}

		if (toDateTime != null) {
			sql.append("AND occurred_at <= ? ");
			params.add(Timestamp.valueOf(toDateTime));
		}

		if (eventType != null && !eventType.isEmpty()) {
			sql.append("AND event_type = ? ");
			params.add(eventType);
		}

		sql.append("ORDER BY occurred_at DESC");

		List<ConsumptionEventDto> eventDtos = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
			Short eventSourceId = rs.getShort("event_source_id");
			String eventSource = eventSourceId == 1 ? "manual" : "auto_estimated";
			Long amount = rs.getObject("amount") != null ? rs.getLong("amount") : null;
			return new ConsumptionEventDto(
				rs.getLong("id"),
				rs.getLong("inventory_item_id"),
				rs.getString("event_type"),
				eventSource,
				rs.getBigDecimal("quantity"),
				amount,
				rs.getObject("occurred_at", LocalDateTime.class)
			);
		}, params.toArray());

		return eventDtos;
	}
}
