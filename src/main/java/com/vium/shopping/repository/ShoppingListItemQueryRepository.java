package com.vium.shopping.repository;

import com.vium.shopping.entity.ShoppingListItem;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * ShoppingListItem 복합 조회 Repository
 *
 * 역할: 복잡한 조회를 처리 (여러 항목, 정렬, 필터링)
 *
 * 구현 방식: JDBC (JdbcTemplate 사용)
 * - SQL을 직접 작성해야 함
 * - 유연하고 강력함
 * - 성능이 좋음
 *
 * TODO 1: JDBC 결과를 Entity로 변환하는 RowMapper 구현 필요
 * TODO 2: 각 메서드에 SQL 작성
 */
@Repository
@RequiredArgsConstructor
public class ShoppingListItemQueryRepository {

	// JdbcTemplate: DB에 SQL을 직접 실행하는 도구
	private final JdbcTemplate jdbcTemplate;

	/**
	 * 사용자의 장보기 리스트 전체 조회
	 *
	 * API: GET /api/me/shopping-list-items
	 * 역할: 사용자의 모든 장보기 항목을 조회하고 정렬해서 반환
	 *
	 * 정렬 규칙:
	 * 1. 체크 안 된 항목부터 (is_checked = false)
	 * 2. 같은 상태 내에서 최신순서 (created_at DESC)
	 *
	 * 예상 반환:
	 * [
	 *   { id: 1, userId: 123, customName: "우유", isChecked: false, createdAt: "2026-09-17" },
	 *   { id: 2, userId: 123, customName: "달걀", isChecked: false, createdAt: "2026-09-16" },
	 *   { id: 3, userId: 123, customName: "치즈", isChecked: true,  createdAt: "2026-09-15" }
	 * ]
	 *
	 * @param userId 사용자 ID
	 * @return 정렬된 장보기 항목 리스트
	 */
	public List<ShoppingListItem> findShoppingListByUserId(Long userId) {
		String sql = """
			SELECT id, user_id, ingredient_catalog_id, custom_name, suggested_quantity,
			       unit_id, reason, is_checked, created_at
			FROM shopping_list_items
			WHERE user_id = ?
			ORDER BY is_checked ASC, created_at DESC
			""";

		// RowMapper: ResultSet의 각 행을 ShoppingListItem 객체로 변환
		return jdbcTemplate.query(sql, (rs, rowNum) -> ShoppingListItem.builder()
			.id(rs.getLong("id"))
			.userId(rs.getLong("user_id"))
			.ingredientCatalogId(rs.getObject("ingredient_catalog_id", Long.class))
			.customName(rs.getString("custom_name"))
			.suggestedQuantity(rs.getBigDecimal("suggested_quantity"))
			.unitId(rs.getObject("unit_id", Short.class))
			.reason(rs.getString("reason"))
			.isChecked(rs.getBoolean("is_checked"))
			.createdAt(rs.getObject("created_at", java.time.LocalDateTime.class))
			.build(), userId);
	}

	/**
	 * 사용자의 미체크 항목만 조회
	 *
	 * 사용처:
	 * - 장보기 도우미 화면에서 "사야 할 것" 항목만 표시
	 *
	 * @param userId 사용자 ID
	 * @return 미체크 항목들
	 */
	public List<ShoppingListItem> findUncheckedItemsByUserId(Long userId) {
		String sql = """
			SELECT id, user_id, ingredient_catalog_id, custom_name, suggested_quantity,
			       unit_id, reason, is_checked, created_at
			FROM shopping_list_items
			WHERE user_id = ? AND is_checked = false
			ORDER BY created_at DESC
			""";

		// TODO: RowMapper 구현
		return List.of();
	}

	/**
	 * 사용자의 특정 재료 항목 개수 조회
	 *
	 * 사용처:
	 * - POST 시: 중복 확인 (같은 ingredient_catalog_id로 이미 있는가?)
	 * - 개수 > 0 이면 이미 있는 항목
	 *
	 * @param userId              사용자 ID
	 * @param ingredientCatalogId 재료 카탈로그 ID
	 * @return 항목 개수 (0 또는 1)
	 */
	public long countByUserIdAndIngredientCatalogId(Long userId, Long ingredientCatalogId) {
		String sql = """
			SELECT COUNT(*) FROM shopping_list_items
			WHERE user_id = ? AND ingredient_catalog_id = ?
			""";

		Integer count = jdbcTemplate.queryForObject(sql, Integer.class, userId, ingredientCatalogId);
		return count != null ? count.longValue() : 0L;
	}
}
