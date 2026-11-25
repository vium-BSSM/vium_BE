package com.vium.notification.repository;

import com.vium.notification.dto.ShoppingReminderPushCandidate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 장보기 리마인더 대상 사용자를 조회한다. 리마인더 행은 발송일 00:00을 scheduled_at으로 가지므로
 * (user_id, scheduled_at)이 하루 한 건의 키다. 그날 이미 성공 발송된 사용자는 제외하고,
 * sent_at이 NULL인 행(이전 전송 실패)이 있으면 그 id를 돌려줘 같은 행으로 재시도한다.
 * 보유 재료 수는 푸시 문구에만 쓰며, 기준은 status=active인 재고 수다.
 */
@Repository
@RequiredArgsConstructor
public class ShoppingReminderPushQueryRepository {

	private final JdbcTemplate jdbcTemplate;

	public List<ShoppingReminderPushCandidate> findCandidates(LocalDateTime scheduledAt) {
		String sql = """
			select u.id as user_id, n.id as existing_notification_id,
			       (select count(*) from inventory_items i
			        join item_statuses s on s.id = i.status_id and s.code = 'active'
			        where i.user_id = u.id) as active_item_count
			from users u
			left join "notifications" n
			       on n.user_id = u.id and n.type = 'shopping_reminder' and n.scheduled_at = ?
			where u.shopping_reminder_enabled = true
			  and u.push_enabled = true
			  and u.deleted_at is null
			  and exists (select 1 from "user_push_tokens" t where t.user_id = u.id)
			  and (n.id is null or n.sent_at is null)
			order by u.id
			""";
		return jdbcTemplate.query(sql, (rs, rowNum) -> new ShoppingReminderPushCandidate(
			rs.getLong("user_id"),
			rs.getLong("active_item_count"),
			(Long) rs.getObject("existing_notification_id")
		), scheduledAt);
	}
}
