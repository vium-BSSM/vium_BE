package com.vium.post.service;

import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.global.exception.NotFoundException;
import com.vium.inventory.dto.IngredientDetailResponse;
import com.vium.inventory.service.InventoryService;
import com.vium.notification.service.NotificationService;
import com.vium.post.repository.PostDetailQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재료 하드 삭제(이슈 8번). 재고 자체의 변경(존재·소유 확인, 실제 삭제)은 InventoryService를 거치고,
 * 게시물·거래 이력 확인은 post 도메인이 가진 읽기 전용 참조(posts, trades)로 직접 한다.
 */
@Service
@RequiredArgsConstructor
public class IngredientDeleteService {

	private static final String ACTIVE_STATUS_CODE = "active";

	private final InventoryService inventoryService;
	private final NotificationService notificationService;
	private final PostDetailQueryRepository postDetailQueryRepository;

	@Transactional
	public void delete(Long userId, Long inventoryItemId) {
		IngredientDetailResponse detail = inventoryService.getDetail(userId, inventoryItemId);
		if (!ACTIVE_STATUS_CODE.equals(detail.statusCode())) {
			throw new NotFoundException("식재료를 찾을 수 없습니다");
		}

		if (inventoryService.isLocked(inventoryItemId)) {
			throw new BusinessException(ErrorCode.INVENTORY_LOCKED, "게시물에 올라간 재고는 삭제할 수 없습니다.");
		}

		boolean hasConsumptionEvents = inventoryService.hasConsumptionEvents(userId, inventoryItemId);
		boolean hasPostHistory = postDetailQueryRepository.existsAnyPostForInventoryItem(inventoryItemId);
		boolean receivedFromTrade = postDetailQueryRepository.existsTradeReceivingInventoryItem(inventoryItemId);
		if (hasConsumptionEvents || hasPostHistory || receivedFromTrade) {
			throw new BusinessException(ErrorCode.INVENTORY_HAS_HISTORY);
		}

		notificationService.deleteByInventoryItemId(inventoryItemId);
		inventoryService.hardDelete(userId, inventoryItemId);
	}
}
