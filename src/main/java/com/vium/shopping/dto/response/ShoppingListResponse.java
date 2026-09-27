package com.vium.shopping.dto.response;

import com.vium.shopping.entity.ShoppingListItem;
import java.util.List;

/**
 * 장보기 리스트 조회 응답 DTO
 *
 * API: GET /api/me/shopping-list-items
 * 역할: 사용자의 모든 장보기 항목을 리스트로 반환
 *
 * 전체 API 응답 형식:
 * {
 *   "success": true,
 *   "data": {
 *     "items": [
 *       { 항목 1 },
 *       { 항목 2 },
 *       ...
 *     ]
 *   },
 *   "error": null
 * }
 *
 * 예시:
 * {
 *   "success": true,
 *   "data": {
 *     "items": [
 *       {
 *         "shoppingListItemId": 1,
 *         "ingredientCatalogId": 5,
 *         "customName": "우유",
 *         "suggestedQuantity": 500,
 *         "unitId": 1,
 *         "unitName": "ml",
 *         "reason": "지난달 절반을 버림",
 *         "isChecked": false,
 *         "createdAt": "2026-09-17T10:30:00"
 *       },
 *       {
 *         "shoppingListItemId": 2,
 *         "ingredientCatalogId": 8,
 *         "customName": "달걀",
 *         "suggestedQuantity": 8,
 *         "unitId": 2,
 *         "unitName": "개",
 *         "reason": "자주 버림",
 *         "isChecked": true,
 *         "createdAt": "2026-09-16T15:20:00"
 *       }
 *     ]
 *   },
 *   "error": null
 * }
 */
public record ShoppingListResponse(
	/** 사용자의 모든 장보기 항목 */
	List<ShoppingListItemResponse> items
) {

	/**
	 * Entity 리스트를 DTO로 변환하는 팩토리 메서드
	 *
	 * List<ShoppingListItem> → ShoppingListResponse
	 *
	 * @param items entity 리스트
	 * @return DTO
	 */
	public static ShoppingListResponse from(List<ShoppingListItem> items) {
		List<ShoppingListItemResponse> responses = items.stream()
			.map(ShoppingListItemResponse::from)
			.toList();
		return new ShoppingListResponse(responses);
	}

	/**
	 * 빈 응답 생성 (항목이 없을 때)
	 *
	 * @return 빈 items 배열을 가진 응답
	 */
	public static ShoppingListResponse empty() {
		return new ShoppingListResponse(List.of());
	}
}
