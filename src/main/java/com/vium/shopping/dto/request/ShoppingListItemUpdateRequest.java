package com.vium.shopping.dto.request;

/**
 * 장보기 리스트 항목 체크 상태 변경 요청 DTO
 *
 * API: PATCH /api/me/shopping-list-items/{itemId}
 * 역할: HTTP 요청의 JSON을 Java 객체로 변환
 *
 * 요청 예시:
 * {
 *   "isChecked": true
 * }
 *
 * 의미:
 * - true: 구매 완료 표시
 * - false: 구매 취소
 */
public record ShoppingListItemUpdateRequest(
	/** 체크 상태 (true=구매 완료, false=미완료) */
	Boolean isChecked
) {
}
