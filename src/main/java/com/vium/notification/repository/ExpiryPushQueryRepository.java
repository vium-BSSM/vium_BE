package com.vium.notification.repository;

import com.vium.notification.dto.ExpiryPushCandidate;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 소비기한 임박 푸시 대상(사용자 x 재고)을 한 번의 조인으로 조회한다. 날짜 연산은 SQL에서 하지 않고
 * 하한(오늘)·상한(오늘 + 알림 일수 최대값)만 자바에서 계산해 바인딩한다. 사용자별 expiry_alert_days
 * 상한 필터는 호출부(자바)에서 적용한다. 하한(expires_on >= 오늘)을 제외하면 기준은
 * InventoryQueryRepository.findActiveIngredients와 동일해야 한다: status=active,
 * expires_on IS NOT NULL인 행만, remaining_quantity 조건 없음, 재고 1건당 1행.
 *
 * notifications를 LEFT JOIN해 이미 성공 발송된(type='expiry', sent_at NOT NULL) 재고는 제외하고,
 * sent_at이 NULL인 기존 행이 있으면 그 행의 id를 existingNotificationId로 돌려줘 재시도 경로로 넘긴다.
 */
@Repository
@RequiredArgsConstructor
public class ExpiryPushQueryRepository {

	private final JdbcTemplate jdbcTemplate;

	public List<ExpiryPushCandidate> findCandidates(LocalDate lowerBound, LocalDate upperBound) {
		String sql = """
			select i.user_id, i.id as inventory_item_id,
			       coalesce(c.name, i.custom_name) as ingredient_name,
			       i.expires_on, u.expiry_alert_days, n.id as existing_notification_id
			from inventory_items i
			join item_statuses s on s.id = i.status_id and s.code = 'active'
			join users u on u.id = i.user_id
			left join ingredient_catalog c on c.id = i.ingredient_catalog_id
			left join "notifications" n
			       on n.user_id = i.user_id and n.type = 'expiry' and n.inventory_item_id = i.id
			where i.expires_on is not null
			  and i.expires_on >= ?
			  and i.expires_on <= ?
			  and u.push_enabled = true
			  and u.deleted_at is null
			  and exists (select 1 from "user_push_tokens" t where t.user_id = u.id)
			  and (n.id is null or n.sent_at is null)
			order by i.user_id, i.expires_on asc, i.id asc
			""";
		return jdbcTemplate.query(sql, (rs, rowNum) -> new ExpiryPushCandidate(
			rs.getLong("user_id"),
			rs.getLong("inventory_item_id"),
			rs.getString("ingredient_name"),
			rs.getObject("expires_on", LocalDate.class),
			rs.getInt("expiry_alert_days"),
			(Long) rs.getObject("existing_notification_id")
		), lowerBound, upperBound);
	}
}
