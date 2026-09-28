package com.vium.shopping.dto.response;

import com.vium.shopping.entity.ShoppingListItem;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 장보기 리스트 항목 응답 DTO
 *
 * 역할: 장보기 항목 1개를 API 응답으로 변환
 *
 * 사용처:
 * - GET /api/me/shopping-list-items 응답 (항목 배열에 포함)
 * - POST /api/me/shopping-list-items 응답 (추가된 항목)
 * - PATCH /api/me/shopping-list-items/{id} 응답 (수정된 항목)
 *
 * 예시 JSON:
 * {
 *   "shoppingListItemId": 1,
 *   "ingredientCatalogId": 5,
 *   "customName": "우유",
 *   "suggestedQuantity": 500,
 *   "unitId": 4,
 *   "unitName": "ml",
 *   "reason": "지난달 절반을 버림",
 *   "isChecked": false,
 *   "createdAt": "2026-09-17T10:30:00"
 * }
 */
public record ShoppingListItemResponse(
	/** 장보기 항목 ID */
	Long shoppingListItemId,

	/** 식재료 카탈로그 ID (nullable) */
	Long ingredientCatalogId,

	/** 사용자 입력 재료명 (nullable) */
	String customName,

	/** 제안 수량 */
	BigDecimal suggestedQuantity,

	/** 수량 단위 ID */
	Short unitId,

	/** 수량 단위명 (units 테이블에서 조회) */
	String unitName,

	/** 제안 이유 */
	String reason,

	/** 체크 상태 (true=구매 완료, false=미완료) */
	Boolean isChecked,

	/** 생성 시간 */
	LocalDateTime createdAt
) {

	/**
	 * Entity를 DTO로 변환하는 팩토리 메서드
	 *
	 * ShoppingListItem entity → ShoppingListItemResponse DTO
	 *
	 * @param item 변환할 entity
	 * @return DTO
	 */
	public static ShoppingListItemResponse from(ShoppingListItem item) {
		return new ShoppingListItemResponse(
			item.getId(),
			item.getIngredientCatalogId(),
			item.getCustomName(),
			item.getSuggestedQuantity(),
			item.getUnitId(),
			null,  // TODO: unitName은 추후 추가 (units 테이블 조회 필요)
			item.getReason(),
			item.getIsChecked(),
			item.getCreatedAt()
		);
	}
}
