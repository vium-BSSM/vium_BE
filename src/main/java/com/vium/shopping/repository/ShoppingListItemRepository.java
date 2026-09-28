package com.vium.shopping.repository;

import com.vium.shopping.entity.ShoppingListItem;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * ShoppingListItem JPA Repository
 *
 * 역할: 장보기 리스트 항목의 기본 CRUD 연산
 *
 * Spring Data JPA가 제공하는 자동 메서드:
 * - save(entity)       : 저장 또는 수정
 * - findById(id)       : ID로 조회
 * - delete(entity)     : 삭제
 * - findAll()          : 모두 조회
 * - deleteById(id)     : ID로 삭제
 *
 * 우리가 정의하는 메서드:
 * - 메서드명으로 자동 쿼리 생성
 */
@Repository
public interface ShoppingListItemRepository extends JpaRepository<ShoppingListItem, Long> {

	/**
	 * 사용자별 항목 조회 (ID와 userId로)
	 *
	 * SQL: SELECT * FROM shopping_list_items WHERE id = ? AND user_id = ?
	 *
	 * 사용처:
	 * - DELETE 시: 소유권 확인 후 삭제
	 * - UPDATE 시: 소유권 확인 후 수정
	 *
	 * @param id       항목 ID
	 * @param userId   사용자 ID
	 * @return 항목 (없으면 empty)
	 */
	Optional<ShoppingListItem> findByIdAndUserId(Long id, Long userId);

	/**
	 * 사용자별 중복 확인
	 *
	 * SQL: SELECT * FROM shopping_list_items WHERE user_id = ? AND ingredient_catalog_id = ?
	 *
	 * 사용처:
	 * - POST 시: 같은 재료가 이미 있는지 확인
	 * - 중복이면 예외 처리
	 *
	 * @param userId              사용자 ID
	 * @param ingredientCatalogId 재료 카탈로그 ID
	 * @return 항목 (없으면 empty)
	 */
	Optional<ShoppingListItem> findByUserIdAndIngredientCatalogId(Long userId, Long ingredientCatalogId);

	/**
	 * 사용자별 항목 삭제
	 *
	 * SQL: DELETE FROM shopping_list_items WHERE id = ? AND user_id = ?
	 *
	 * 사용처:
	 * - DELETE /api/me/shopping-list-items/{itemId}
	 * - 소유권 검증 후 삭제
	 *
	 * @param id     항목 ID
	 * @param userId 사용자 ID
	 * @return 삭제된 행 수
	 */
	long deleteByIdAndUserId(Long id, Long userId);

	// ============================================
	// 주의: 복합 조회 (여러 항목)는
	//      ShoppingListItemQueryRepository에서 처리
	// ============================================
}
