package com.vium.notification.repository;

import com.vium.notification.dto.ConsumeCheckPushCandidate;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ConsumeCheckPushQueryRepository {

	private final JdbcTemplate jdbcTemplate;

	public List<ConsumeCheckPushCandidate> findCandidates(LocalDate today) {
		String sql = """
			select i.user_id, i.id as inventory_item_id,
			       coalesce(c.name, i.custom_name) as ingredient_name,
			       n.id as existing_notification_id
			from inventory_items i
			join item_statuses s on s.id = i.status_id and s.code = 'active'
			join users u on u.id = i.user_id
			left join ingredient_catalog c on c.id = i.ingredient_catalog_id
			left join "notifications" n
			       on n.user_id = i.user_id and n.type = 'consume_check' and n.inventory_item_id = i.id
			where i.expires_on = ?
			  and i.remaining_quantity > 0
			  and i.keep_confirmed_expires_on is distinct from i.expires_on
			  and u.push_enabled = true
			  and u.deleted_at is null
			  and exists (select 1 from "user_push_tokens" t where t.user_id = u.id)
			  and (n.id is null or n.sent_at is null)
			order by i.user_id, i.id
			""";
		return jdbcTemplate.query(sql, (rs, rowNum) -> new ConsumeCheckPushCandidate(
			rs.getLong("user_id"),
			rs.getLong("inventory_item_id"),
			rs.getString("ingredient_name"),
			rs.getObject("existing_notification_id", Long.class)
		), today);
	}
}
