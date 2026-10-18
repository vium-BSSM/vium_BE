package com.vium.dispose.repository;

import com.vium.dispose.dto.ConsumptionEventDto;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ConsumptionEventQueryRepository {

	private final JdbcTemplate jdbcTemplate;
	private final Clock clock;

	public List<ConsumptionEventDto> findEvents(Long userId, LocalDate from, LocalDate to, String eventType) {
		// from: 해당 일자 00:00 이상. to: 해당 일자 포함(다음 날 00:00 미만), '<' 비교.
		LocalDateTime fromDateTime = from != null ? from.atStartOfDay() : null;
		LocalDateTime toExclusiveDateTime = to != null ? to.plusDays(1).atStartOfDay() : null;

		StringBuilder sqlWithJoin = new StringBuilder("""
			SELECT ce.id, ce.inventory_item_id, ce.event_type, es.code as event_source, ce.quantity, ce.amount, ce.occurred_at
			FROM "consumption_events" ce
			JOIN "event_sources" es ON ce.event_source_id = es.id
			WHERE ce.user_id = ?
			""");

		List<Object> params = new ArrayList<>();
		params.add(userId);

		if (fromDateTime != null) {
			sqlWithJoin.append("AND ce.occurred_at >= ? ");
			params.add(Timestamp.valueOf(fromDateTime));
		}

		if (toExclusiveDateTime != null) {
			sqlWithJoin.append("AND ce.occurred_at < ? ");
			params.add(Timestamp.valueOf(toExclusiveDateTime));
		}

		if (eventType != null && !eventType.isEmpty()) {
			sqlWithJoin.append("AND ce.event_type = ? ");
			params.add(eventType);
		}

		sqlWithJoin.append("ORDER BY ce.occurred_at DESC");

		List<ConsumptionEventDto> eventDtos = jdbcTemplate.query(sqlWithJoin.toString(), (rs, rowNum) -> {
			Long amount = rs.getObject("amount") != null ? rs.getLong("amount") : null;
			LocalDateTime localDateTime = rs.getObject("occurred_at", LocalDateTime.class);
			// DB에 저장된 값은 Asia/Seoul 기준 벽시계 시각(naive)이다. 응답은 UTC(Z)로 변환한다.
			OffsetDateTime offsetDateTime = localDateTime.atZone(clock.getZone())
				.toOffsetDateTime()
				.withOffsetSameInstant(ZoneOffset.UTC);
			return new ConsumptionEventDto(
				rs.getLong("id"),
				rs.getLong("inventory_item_id"),
				rs.getString("event_type"),
				rs.getString("event_source"),
				rs.getBigDecimal("quantity"),
				amount,
				offsetDateTime
			);
		}, params.toArray());

		return eventDtos;
	}
}
