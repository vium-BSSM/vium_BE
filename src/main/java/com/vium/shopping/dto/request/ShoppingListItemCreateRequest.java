package com.vium.shopping.dto.request;

import java.math.BigDecimal;
import jakarta.validation.constraints.NotNull;

/**
 * 장보기 리스트 항목 추가 요청 DTO
 *
 * API: POST /api/me/shopping-list-items
 * 역할: HTTP 요청의 JSON을 Java 객체로 변환
 *
 * 요청 예시:
 * {
 *   "ingredientCatalogId": 5,
 *   "customName": null,
 *   "suggestedQuantity": 1000,
 *   "unitId": 1,
 *   "reason": "낭비 이력 기반 재구매 제안"
 * }
 *
 * 검증 규칙:
 * - ingredientCatalogId 또는 customName 중 하나는 반드시 있어야 함
 *   (카탈로그에 있는 재료이거나, 사용자가 직접 입력한 재료)
 */
public record ShoppingListItemCreateRequest(
	/** 식재료 카탈로그 ID (nullable) */
	Long ingredientCatalogId,

	/** 사용자 입력 재료명 (nullable) */
	String customName,

	/** 제안 수량 */
	BigDecimal suggestedQuantity,

	/** 수량 단위 ID */
	Short unitId,

	/** 제안 이유 */
	String reason
) {

	/**
	 * 요청 검증 (Service에서 호출)
	 *
	 * 검증 항목:
	 * 1. ingredientCatalogId 또는 customName 중 하나는 반드시 존재
	 *    (둘 다 null이면 어떤 재료인지 알 수 없음)
	 * 2. customName이 주어진 경우, 공백만 있으면 null로 처리
	 *
	 * @return customName이 정규화된 요청 객체
	 * @throws IllegalArgumentException 검증 실패 시
	 */
	public ShoppingListItemCreateRequest validate() {
		// customName을 정규화 (공백 제거)
		String normalizedCustomName = (customName != null && !customName.isBlank())
			? customName.trim()
			: null;

		// 둘 다 null이면 에러
		if (ingredientCatalogId == null && normalizedCustomName == null) {
			throw new IllegalArgumentException(
				"ingredientCatalogId 또는 customName 중 하나는 반드시 필요합니다");
		}

		// 정규화된 customName으로 새로운 요청 객체 반환
		return new ShoppingListItemCreateRequest(
			ingredientCatalogId,
			normalizedCustomName,
			suggestedQuantity,
			unitId,
			reason
		);
	}
}
