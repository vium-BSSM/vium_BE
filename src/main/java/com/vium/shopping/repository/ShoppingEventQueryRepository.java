package com.vium.shopping.repository;

import java.math.BigDecimal;
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
public class ShoppingEventQueryRepository {

	private final JdbcTemplate jdbcTemplate;

	/**
	 * 폐기/소진 이벤트를 재료 정보와 함께 조회.
	 *
	 * inventory_items 상태와 무관하게 모든 재료 포함.
	 * (disposed/consumed 상태의 재료도 조회 가능)
	 *
	 * @param userId 사용자 ID
	 * @param from 시작일 (포함), null이면 제한 없음
	 * @param to 종료일 (포함), null이면 제한 없음
	 * @param eventType 'disposed' 또는 'consumed'
	 * @return 이벤트와 재료 정보
	 */
	public List<EventWithItemInfo> findEventsWithItemInfo(
		Long userId, LocalDate from, LocalDate to, String eventType) {

		LocalDateTime fromDateTime = from != null ? from.atStartOfDay() : null;
		LocalDateTime toDateTime = to != null ? to.atTime(LocalTime.MAX) : null;

		StringBuilder sql = new StringBuilder("""
			SELECT
				ce.inventory_item_id,
				ce.quantity,
				i.unit_id,
				u.name as unit_name,
				i.ingredient_catalog_id,
				COALESCE(i.custom_name, ic.name) as ingredient_name
			FROM "consumption_events" ce
			JOIN "inventory_items" i ON ce.inventory_item_id = i.id
			LEFT JOIN "ingredient_catalog" ic ON i.ingredient_catalog_id = ic.id
			LEFT JOIN "units" u ON i.unit_id = u.id
			WHERE ce.user_id = ?
			""");

		List<Object> params = new ArrayList<>();
		params.add(userId);

		if (fromDateTime != null) {
			sql.append("AND ce.occurred_at >= ? ");
			params.add(Timestamp.valueOf(fromDateTime));
		}

		if (toDateTime != null) {
			sql.append("AND ce.occurred_at <= ? ");
			params.add(Timestamp.valueOf(toDateTime));
		}

		if (eventType != null && !eventType.isEmpty()) {
			sql.append("AND ce.event_type = ? ");
			params.add(eventType);
		}

		return jdbcTemplate.query(sql.toString(), (rs, rowNum) ->
			new EventWithItemInfo(
				rs.getLong("inventory_item_id"),
				rs.getBigDecimal("quantity"),
				rs.getShort("unit_id"),
				rs.getString("unit_name"),
				rs.getObject("ingredient_catalog_id", Long.class),
				rs.getString("ingredient_name")
			),
			params.toArray());
	}

	/**
	 * 이벤트와 재료 정보를 포함한 DTO.
	 */
	public record EventWithItemInfo(
		Long inventoryItemId,
		BigDecimal quantity,
		Short unitId,
		String unitName,
		Long ingredientCatalogId,
		String ingredientName
	) {}
}
