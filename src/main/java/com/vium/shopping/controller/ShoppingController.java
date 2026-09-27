package com.vium.shopping.controller;

import com.vium.global.common.ApiResponse;
import com.vium.global.security.CurrentUserProvider;
import com.vium.shopping.dto.request.ShoppingListItemCreateRequest;
import com.vium.shopping.dto.request.ShoppingListItemUpdateRequest;
import com.vium.shopping.dto.response.ShoppingListItemResponse;
import com.vium.shopping.dto.response.ShoppingListResponse;
import com.vium.shopping.service.ShoppingListService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Shopping API Controller
 *
 * 역할:
 * 1. HTTP 요청 수신
 * 2. 요청 데이터 검증 및 매핑
 * 3. 현재 사용자 ID 추출
 * 4. Service 호출
 * 5. 응답 반환 (JSON)
 *
 * 현재 구현: 장보기 리스트 조회 API만
 */
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class ShoppingController {

	// 의존성 주입
	private final ShoppingListService shoppingListService;
	private final CurrentUserProvider currentUserProvider;

	/**
	 * 장보기 리스트 항목 추가 엔드포인트
	 *
	 * API 명세:
	 * - HTTP Method: POST
	 * - URL: /api/me/shopping-list-items
	 * - Authentication: Bearer Token (현재 사용자)
	 * - Request Body: ShoppingListItemCreateRequest (JSON)
	 * - Response Code: 201 Created
	 *
	 * 요청 예시:
	 * POST /api/me/shopping-list-items
	 * Content-Type: application/json
	 * Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
	 *
	 * {
	 *   "ingredientCatalogId": 5,
	 *   "customName": null,
	 *   "suggestedQuantity": 1000,
	 *   "unitId": 1,
	 *   "reason": "낭비 이력 기반 재구매 제안"
	 * }
	 *
	 * 응답 예시 (성공 - 201):
	 * {
	 *   "success": true,
	 *   "data": {
	 *     "shoppingListItemId": 1,
	 *     "ingredientCatalogId": 5,
	 *     "customName": null,
	 *     "suggestedQuantity": 1000,
	 *     "unitId": 1,
	 *     "unitName": null,
	 *     "reason": "낭비 이력 기반 재구매 제안",
	 *     "isChecked": false,
	 *     "createdAt": "2026-09-27T20:45:00"
	 *   },
	 *   "error": null
	 * }
	 *
	 * 응답 예시 (실패 - 409):
	 * {
	 *   "success": false,
	 *   "data": null,
	 *   "error": {
	 *     "code": "CONFLICT",
	 *     "message": "이미 장보기 리스트에 있는 항목입니다"
	 *   }
	 * }
	 *
	 * 처리 흐름:
	 * 1. HTTP POST 요청 수신
	 * 2. @Valid로 요청 검증 (JSON 형식)
	 * 3. CurrentUserProvider에서 사용자 ID 추출
	 * 4. Service.addItem(userId, request) 호출
	 * 5. ShoppingListItemResponse 받음
	 * 6. ApiResponse.ok(response)로 감싸기
	 * 7. HTTP 201 Created 응답
	 *
	 * @param request 항목 추가 요청 (JSON)
	 * @return 추가된 항목이 포함된 API 응답
	 * @throws BusinessException 중복 항목이 있는 경우
	 *
	 * HTTP 흐름:
	 * POST Request 진입 (Content-Type: application/json)
	 *   ↓
	 * @PostMapping 라우팅
	 *   ↓
	 * @Valid로 JSON 검증 및 ShoppingListItemCreateRequest로 변환
	 *   ↓
	 * addShoppingListItem() 메서드 실행
	 *   ↓
	 * 현재 사용자 ID 추출: currentUserProvider.getCurrentUserId()
	 *   ↓
	 * Service.addItem(userId, request) 호출
	 *   ↓
	 * Service에서:
	 *   1. request.validate() → 검증 및 정규화
	 *   2. countByUserIdAndIngredientCatalogId() → 중복 확인
	 *   3. ShoppingListItem.builder() → Entity 생성
	 *   4. repository.save() → DB 저장
	 *   5. ShoppingListItemResponse.from() → DTO 변환
	 *   ↓
	 * ShoppingListItemResponse 받음
	 *   ↓
	 * ApiResponse.ok(response)로 감싸기
	 *   ↓
	 * @ResponseStatus(HttpStatus.CREATED) → HTTP 201 설정
	 *   ↓
	 * Spring이 자동으로 JSON 변환
	 *   ↓
	 * HTTP 201 Created 응답
	 */
	@PostMapping("/shopping-list-items")
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<ShoppingListItemResponse> addShoppingListItem(
		@Valid @RequestBody ShoppingListItemCreateRequest request) {
		// Step 1: 현재 사용자 ID 추출
		Long userId = currentUserProvider.getCurrentUserId();

		// Step 2: Service 호출
		// Service가 다음을 수행:
		// - 요청 검증
		// - 중복 확인
		// - Entity 생성 및 저장
		// - DTO로 변환
		ShoppingListItemResponse response = shoppingListService.addItem(userId, request);

		// Step 3: 응답 반환 (HTTP 201 Created)
		return ApiResponse.ok(response);
	}

	/**
	 * 장보기 리스트 항목 삭제 엔드포인트
	 *
	 * API 명세:
	 * - HTTP Method: DELETE
	 * - URL: /api/me/shopping-list-items/{itemId}
	 * - Authentication: Bearer Token (현재 사용자)
	 * - Response Code: 200 OK
	 *
	 * 요청 예시:
	 * DELETE /api/me/shopping-list-items/1
	 * Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
	 *
	 * 응답 예시 (성공 - 200):
	 * {
	 *   "success": true,
	 *   "data": null,
	 *   "error": null
	 * }
	 *
	 * 응답 예시 (실패 - 404):
	 * {
	 *   "success": false,
	 *   "data": null,
	 *   "error": {
	 *     "code": "NOT_FOUND",
	 *     "message": "항목을 찾을 수 없습니다"
	 *   }
	 * }
	 *
	 * 처리 흐름:
	 * 1. HTTP DELETE 요청 수신
	 * 2. @PathVariable에서 itemId 추출
	 * 3. CurrentUserProvider에서 사용자 ID 추출
	 * 4. Service.deleteItem(userId, itemId) 호출
	 * 5. ApiResponse.ok(null)로 감싸기
	 * 6. HTTP 200 OK 응답
	 *
	 * @param itemId 삭제할 항목 ID
	 * @throws NotFoundException 항목을 찾을 수 없는 경우
	 *
	 * HTTP 흐름:
	 * DELETE Request 진입
	 *   ↓
	 * @DeleteMapping 라우팅
	 *   ↓
	 * deleteItem() 메서드 실행
	 *   ↓
	 * @PathVariable에서 itemId 추출: 1
	 *   ↓
	 * 현재 사용자 ID 추출: currentUserProvider.getCurrentUserId()
	 *   ↓
	 * Service.deleteItem(userId, itemId) 호출
	 *   ↓
	 * Service에서:
	 *   1. repository.findByIdAndUserId(itemId, userId) → 소유권 확인
	 *   2. repository.delete(item) → DB 삭제
	 *   ↓
	 * void 반환 (데이터 없음)
	 *   ↓
	 * ApiResponse.ok(null)로 감싸기
	 *   ↓
	 * Spring이 자동으로 JSON 변환
	 *   ↓
	 * HTTP 200 OK 응답
	 */
	@DeleteMapping("/shopping-list-items/{itemId}")
	public ApiResponse<Void> deleteItem(@PathVariable Long itemId) {
		// Step 1: 현재 사용자 ID 추출
		Long userId = currentUserProvider.getCurrentUserId();

		// Step 2: Service 호출
		// Service가 다음을 수행:
		// - 항목 조회 및 소유권 확인
		// - DB 삭제
		shoppingListService.deleteItem(userId, itemId);

		// Step 3: 응답 반환 (HTTP 200 OK, 데이터 없음)
		return ApiResponse.ok(null);
	}

	/**
	 * 장보기 리스트 항목 체크 상태 변경 엔드포인트
	 *
	 * API 명세:
	 * - HTTP Method: PATCH
	 * - URL: /api/me/shopping-list-items/{itemId}
	 * - Authentication: Bearer Token (현재 사용자)
	 * - Request Body: ShoppingListItemUpdateRequest (JSON)
	 * - Response Code: 200 OK
	 *
	 * 요청 예시:
	 * PATCH /api/me/shopping-list-items/1
	 * Content-Type: application/json
	 * Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
	 *
	 * {
	 *   "isChecked": true
	 * }
	 *
	 * 응답 예시 (성공 - 200):
	 * {
	 *   "success": true,
	 *   "data": {
	 *     "shoppingListItemId": 1,
	 *     "ingredientCatalogId": null,
	 *     "customName": "우유",
	 *     "suggestedQuantity": 1000,
	 *     "unitId": 1,
	 *     "unitName": null,
	 *     "reason": "테스트",
	 *     "isChecked": true,
	 *     "createdAt": "2026-09-27T22:05:00"
	 *   },
	 *   "error": null
	 * }
	 *
	 * 응답 예시 (실패 - 404):
	 * {
	 *   "success": false,
	 *   "data": null,
	 *   "error": {
	 *     "code": "NOT_FOUND",
	 *     "message": "항목을 찾을 수 없습니다"
	 *   }
	 * }
	 *
	 * 처리 흐름:
	 * 1. HTTP PATCH 요청 수신
	 * 2. @Valid로 요청 검증
	 * 3. @PathVariable에서 itemId 추출
	 * 4. CurrentUserProvider에서 사용자 ID 추출
	 * 5. Service.updateCheckStatus(userId, itemId, request) 호출
	 * 6. ShoppingListItemResponse 받음
	 * 7. ApiResponse.ok(response)로 감싸기
	 * 8. HTTP 200 OK 응답
	 *
	 * @param itemId 변경할 항목 ID
	 * @param request 체크 상태 변경 요청
	 * @return 변경된 항목이 포함된 API 응답
	 * @throws NotFoundException 항목을 찾을 수 없는 경우
	 *
	 * HTTP 흐름:
	 * PATCH Request 진입 (Content-Type: application/json)
	 *   ↓
	 * @PatchMapping 라우팅
	 *   ↓
	 * @Valid로 JSON 검증 및 ShoppingListItemUpdateRequest로 변환
	 *   ↓
	 * updateCheckStatus() 메서드 실행
	 *   ↓
	 * @PathVariable에서 itemId 추출: 1
	 *   ↓
	 * 현재 사용자 ID 추출: currentUserProvider.getCurrentUserId()
	 *   ↓
	 * Service.updateCheckStatus(userId, itemId, request) 호출
	 *   ↓
	 * Service에서:
	 *   1. repository.findByIdAndUserId(itemId, userId) → 소유권 확인
	 *   2. item.updateCheckStatus(request.isChecked()) → 상태 변경
	 *   3. repository.save(item) → DB 저장
	 *   4. ShoppingListItemResponse.from() → DTO 변환
	 *   ↓
	 * ShoppingListItemResponse 받음
	 *   ↓
	 * ApiResponse.ok(response)로 감싸기
	 *   ↓
	 * Spring이 자동으로 JSON 변환
	 *   ↓
	 * HTTP 200 OK 응답
	 */
	@PatchMapping("/shopping-list-items/{itemId}")
	public ApiResponse<ShoppingListItemResponse> updateCheckStatus(
		@PathVariable Long itemId,
		@Valid @RequestBody ShoppingListItemUpdateRequest request) {
		// Step 1: 현재 사용자 ID 추출
		Long userId = currentUserProvider.getCurrentUserId();

		// Step 2: Service 호출
		// Service가 다음을 수행:
		// - 항목 조회 및 소유권 확인
		// - 체크 상태 변경
		// - DB 저장
		// - DTO로 변환
		ShoppingListItemResponse response = shoppingListService.updateCheckStatus(
			userId, itemId, request);

		// Step 3: 응답 반환 (HTTP 200 OK)
		return ApiResponse.ok(response);
	}

	/**
	 * 장보기 리스트 조회 엔드포인트
	 *
	 * API 명세:
	 * - HTTP Method: GET
	 * - URL: /api/me/shopping-list-items
	 * - Authentication: Bearer Token (현재 사용자)
	 * - Response Code: 200 OK
	 *
	 * 요청 예시:
	 * GET /api/me/shopping-list-items
	 * Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
	 *
	 * 응답 예시:
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
	 *       }
	 *     ]
	 *   },
	 *   "error": null
	 * }
	 *
	 * 처리 흐름:
	 * 1. HTTP GET 요청 수신
	 * 2. CurrentUserProvider에서 사용자 ID 추출
	 * 3. Service.getItems(userId) 호출
	 * 4. ShoppingListResponse 받음
	 * 5. ApiResponse.ok(response) 로 감싸기
	 * 6. 자동으로 JSON 변환하여 응답
	 *
	 * @return 장보기 항목 리스트가 포함된 API 응답
	 *
	 * HTTP 흐름:
	 * Request 진입
	 *   ↓
	 * @GetMapping 라우팅
	 *   ↓
	 * getShoppingList() 메서드 실행
	 *   ↓
	 * 현재 사용자 ID 추출: currentUserProvider.getCurrentUserId()
	 *   ↓
	 * Service.getItems(userId) 호출
	 *   ↓
	 * ShoppingListResponse 받음
	 *   ↓
	 * ApiResponse.ok(response) 로 감싸기
	 *   ↓
	 * Spring이 자동으로 JSON 변환
	 *   ↓
	 * HTTP 200 응답
	 */
	@GetMapping("/shopping-list-items")
	public ApiResponse<ShoppingListResponse> getShoppingList() {
		// Step 1: 현재 사용자 ID 추출
		Long userId = currentUserProvider.getCurrentUserId();

		// Step 2: Service 호출
		ShoppingListResponse response = shoppingListService.getItems(userId);

		// Step 3: 응답 반환 (HTTP 200 OK)
		return ApiResponse.ok(response);
	}

	// ============================================
	// 추후 구현 예정
	// ============================================
	// @DeleteMapping("/shopping-list-items/{id}") // 항목 삭제
}
