package com.vium.shopping.service;

import com.vium.shopping.dto.response.ShoppingListItemResponse;
import com.vium.shopping.dto.response.ShoppingListResponse;
import com.vium.shopping.entity.ShoppingListItem;
import com.vium.shopping.repository.ShoppingListItemQueryRepository;
import com.vium.shopping.repository.ShoppingListItemRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 장보기 리스트 Service
 *
 * 역할:
 * 1. 비즈니스 로직 처리 (검증, 조합, 필터링)
 * 2. Repository 호출
 * 3. 트랜잭션 관리
 * 4. DTO 변환
 *
 * 호출 흐름:
 * Controller → Service → Repository → DB
 */
@Service
@RequiredArgsConstructor
public class ShoppingListService {

	// 의존성 주입 (생성자 자동 생성 by @RequiredArgsConstructor)
	private final ShoppingListItemRepository shoppingListItemRepository;
	private final ShoppingListItemQueryRepository shoppingListItemQueryRepository;

	// TODO: 필요한 의존성 추가
	// private final UnitRepository unitRepository;  // 단위명 조회용

	/**
	 * 장보기 리스트 조회
	 *
	 * API: GET /api/me/shopping-list-items
	 *
	 * 역할:
	 * 1. 사용자 ID로 모든 항목 조회
	 * 2. 정렬 적용 (미완료 먼저, 최신순서)
	 * 3. Entity를 DTO로 변환
	 * 4. 응답 반환
	 *
	 * 흐름 다이어그램:
	 * Controller(userId)
	 *   ↓
	 * getItems(userId)
	 *   ↓
	 * QueryRepository.findShoppingListByUserId(userId)
	 *   ↓
	 * DB 조회 (SELECT * FROM shopping_list_items WHERE user_id = ?)
	 *   ↓
	 * List<ShoppingListItem> items
	 *   ↓
	 * ShoppingListResponse.from(items)
	 *   ↓
	 * Controller → JSON 변환 → 사용자
	 *
	 * @param userId 사용자 ID
	 * @return 장보기 항목 리스트
	 *
	 * @Transactional(readOnly = true)
	 * - 읽기만 하므로 최적화
	 * - 데이터 수정이 없음
	 */
	@Transactional(readOnly = true)
	public ShoppingListResponse getItems(Long userId) {
		// Step 1: Repository에서 사용자의 모든 항목 조회
		List<ShoppingListItem> items = shoppingListItemQueryRepository
			.findShoppingListByUserId(userId);

		// Step 2: Entity 리스트를 DTO로 변환
		List<ShoppingListItemResponse> responses = items.stream()
			.map(ShoppingListItemResponse::from)
			.toList();

		// Step 3: 응답 반환
		return new ShoppingListResponse(responses);
	}

	// ============================================
	// 추후 구현 예정 메서드들 (지금은 취소)
	// ============================================
	// - addItem()
	// - updateCheckStatus()
	// - deleteItem()
	// - PurchaseSuggestionService와의 통합
}
