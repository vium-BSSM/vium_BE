package com.vium.shopping.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 장보기 리스트 항목 엔티티
 *
 * DB Table: shopping_list_items
 * 역할: 사용자가 장보기 중 담은 항목들을 저장
 *
 * 예시 데이터:
 * ┌────┬─────────┬─────────────────┬──────────────┐
 * │ id │ user_id │ ingredient_id   │ is_checked   │
 * ├────┼─────────┼─────────────────┼──────────────┤
 * │ 1  │ 123     │ 5 (우유)         │ false        │
 * │ 2  │ 123     │ 8 (달걀)         │ true         │
 * └────┴─────────┴─────────────────┴──────────────┘
 */
@Entity
@Table(name = "shopping_list_items")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShoppingListItem {

	// ============================================
	// 기본 필드 (DB 컬럼과 매핑)
	// ============================================

	/** 항목 ID (primary key, 자동 증가) */
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 사용자 ID (항목의 소유자) */
	@Column(nullable = false)
	private Long userId;

	/** 식재료 카탈로그 ID (ingredient_catalog.id) */
	@Column(name = "ingredient_catalog_id")
	private Long ingredientCatalogId;

	/** 사용자 입력 재료명 (카탈로그에 없을 때) */
	@Column(name = "custom_name", length = 120)
	private String customName;

	/** 제안 수량 (예: 500ml) */
	@Column(name = "suggested_quantity")
	private BigDecimal suggestedQuantity;

	/** 수량 단위 ID (units.id) */
	@Column(name = "unit_id")
	private Short unitId;

	/** 제안 이유 (예: "지난달 절반을 버림") */
	@Column(name = "reason", length = 200)
	private String reason;

	/** 구매 완료 여부 (true=장보기 완료, false=미완료) */
	@Column(name = "is_checked", nullable = false)
	@Builder.Default
	private Boolean isChecked = false;

	/** 생성 시간 */
	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	// ============================================
	// 비즈니스 메서드
	// ============================================

	/**
	 * 체크 상태 변경
	 *
	 * 사용 사례: 사용자가 "구매 완료"로 표시할 때
	 * 호출처: ShoppingListService.updateCheckStatus()
	 *
	 * @param checked 새로운 상태
	 */
	public void updateCheckStatus(Boolean checked) {
		this.isChecked = checked;
	}

	/**
	 * 현재 사용자의 항목인지 확인 (보안 검증)
	 *
	 * 사용 사례: Controller에서 다른 사용자 데이터 접근 방지
	 * 호출처: Service.delete(), Service.update()
	 *
	 * @param userId 확인할 사용자 ID
	 * @return true: 소유자, false: 다른 사용자
	 */
	public boolean isOwnedBy(Long userId) {
		return this.userId.equals(userId);
	}
}
