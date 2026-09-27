package com.vium.shopping.service;

import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.shopping.dto.request.ShoppingListItemCreateRequest;
import com.vium.shopping.dto.response.ShoppingListItemResponse;
import com.vium.shopping.dto.response.ShoppingListResponse;
import com.vium.shopping.entity.ShoppingListItem;
import com.vium.shopping.repository.ShoppingListItemQueryRepository;
import com.vium.shopping.repository.ShoppingListItemRepository;
import java.time.LocalDateTime;
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

	/**
	 * 장보기 리스트에 항목 추가
	 *
	 * API: POST /api/me/shopping-list-items
	 *
	 * 역할:
	 * 1. 요청 검증 (ingredientCatalogId 또는 customName 필수)
	 * 2. 중복 확인 (같은 재료가 이미 있는가?)
	 * 3. Entity 생성 및 저장
	 * 4. DTO로 변환해서 응답
	 *
	 * 흐름 다이어그램:
	 * Controller(userId, request)
	 *   ↓
	 * addItem(userId, request)
	 *   ↓
	 * 1. request.validate() → 요청 검증 및 정규화
	 *   ↓
	 * 2. queryRepository.countByUserIdAndIngredientCatalogId() → 중복 확인
	 *   ↓ (중복이면 예외 발생)
	 * 3. ShoppingListItem.builder() → Entity 생성
	 *   ↓
	 * 4. repository.save() → DB에 저장
	 *   ↓
	 * 5. ShoppingListItemResponse.from() → DTO 변환
	 *   ↓
	 * Controller → JSON 응답
	 *
	 * @param userId 현재 사용자 ID
	 * @param request 항목 추가 요청
	 * @return 추가된 항목의 응답
	 * @throws BusinessException 검증 실패 또는 중복 시
	 *
	 * @Transactional
	 * - 저장 작업이므로 트랜잭션 필수
	 * - 검증과 저장이 함께 성공하거나 모두 실패
	 */
	@Transactional
	public ShoppingListItemResponse addItem(Long userId, ShoppingListItemCreateRequest request) {
		// Step 1: 요청 검증 및 정규화
		// - ingredientCatalogId 또는 customName 중 하나는 필수
		// - customName의 공백 제거
		ShoppingListItemCreateRequest validatedRequest = request.validate();

		// Step 2: 중복 확인
		// 같은 ingredient_catalog_id로 이미 항목이 있으면 추가하지 않음
		// 예: 우유(id=5)가 이미 리스트에 있는데 또 추가하려는 경우
		if (validatedRequest.ingredientCatalogId() != null) {
			long existingCount = shoppingListItemQueryRepository
				.countByUserIdAndIngredientCatalogId(userId, validatedRequest.ingredientCatalogId());

			if (existingCount > 0) {
				throw new BusinessException(
					ErrorCode.CONFLICT,
					"이미 장보기 리스트에 있는 항목입니다"
				);
			}
		}

		// Step 3: Entity 생성
		// Builder 패턴으로 필드별로 값 설정
		ShoppingListItem item = ShoppingListItem.builder()
			.userId(userId)
			.ingredientCatalogId(validatedRequest.ingredientCatalogId())
			.customName(validatedRequest.customName())
			.suggestedQuantity(validatedRequest.suggestedQuantity())
			.unitId(validatedRequest.unitId())
			.reason(validatedRequest.reason())
			.isChecked(false)  // 새로 추가되는 항목은 항상 미완료 상태
			.createdAt(LocalDateTime.now())  // 현재 시간으로 생성
			.build();

		// Step 4: DB에 저장
		// Repository가 INSERT 쿼리 실행
		ShoppingListItem saved = shoppingListItemRepository.save(item);

		// Step 5: Entity를 DTO로 변환해서 응답
		return ShoppingListItemResponse.from(saved);
	}

	// ============================================
	// 추후 구현 예정 메서드들
	// ============================================
	// - updateCheckStatus()
	// - deleteItem()
	// - PurchaseSuggestionService와의 통합
}
