package com.vium.receipt.repository;

import com.vium.receipt.dto.PurchaseSummary;
import com.vium.receipt.entity.PurchaseStatus;
import com.vium.receipt.entity.PurchaseType;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PurchaseQueryRepository {

	private final JdbcTemplate jdbcTemplate;

	// 날짜 범위는 서비스에서 서울 시각의 [시작일 00:00, 종료일 다음 날 00:00)로 변환한다.
	public List<PurchaseSummary> findPurchases(Long userId, PurchaseType type, PurchaseStatus status,
			LocalDateTime fromInclusive, LocalDateTime toExclusive) {
		String sql = """
			select p.id, p.type, p.status, s.name as purchase_source_name,
			       p.purchased_at, p.total_amount,
			       (select count(*) from purchase_items i where i.purchase_id = p.id) as item_count
			from purchases p
			left join purchase_sources s on s.id = p.purchase_source_id
			where p.user_id = ?
			""";
		List<Object> parameters = new ArrayList<>();
		parameters.add(userId);
		if (type != null) {
			sql += " and p.type = ?";
			parameters.add(type.name());
		}
		if (status != null) {
			sql += " and p.status = ?";
			parameters.add(status.name());
		}
		if (fromInclusive != null) {
			sql += " and p.purchased_at >= ?";
			parameters.add(fromInclusive);
		}
		if (toExclusive != null) {
			sql += " and p.purchased_at < ?";
			parameters.add(toExclusive);
		}
		sql += " order by p.purchased_at desc, p.id desc";
		return jdbcTemplate.query(sql, (rs, rowNum) -> new PurchaseSummary(
			rs.getLong("id"), PurchaseType.valueOf(rs.getString("type")),
			PurchaseStatus.valueOf(rs.getString("status")), rs.getString("purchase_source_name"),
			rs.getObject("purchased_at", LocalDateTime.class), rs.getBigDecimal("total_amount"),
			rs.getLong("item_count")), parameters.toArray());
	}
}
