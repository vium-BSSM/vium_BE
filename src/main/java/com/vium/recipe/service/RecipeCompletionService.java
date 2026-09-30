package com.vium.recipe.service;

import com.vium.dispose.service.DisposeService;
import com.vium.dispose.dto.UpdateInventoryItemStatusRequest;
import com.vium.global.code.ItemStatusRepository;
import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.global.exception.NotFoundException;
import com.vium.inventory.entity.InventoryItem;
import com.vium.inventory.repository.InventoryItemRepository;
import com.vium.recipe.dto.RecipeCompleteRequest;
import com.vium.recipe.repository.RecipeSuggestionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RecipeCompletionService {

	private final RecipeSuggestionRepository recipeSuggestionRepository;
	private final InventoryItemRepository inventoryItemRepository;
	private final ItemStatusRepository itemStatusRepository;
	private final DisposeService disposeService;

	private static final String STATUS_CONSUMED = "consumed";

	@Transactional
	public void completeRecipe(Long userId, Long recipeId, RecipeCompleteRequest request) {
		// 1. 중복 검사
		validateNoDuplicateInventory(request.usages());

		// 2. 레시피 접근 권한 확인
		recipeSuggestionRepository.findByUserIdAndRecipeId(userId, recipeId)
			.orElseThrow(() -> new NotFoundException(ErrorCode.RECIPE_NOT_FOUND.getDefaultMessage()));

		// 3. 모든 재고를 한 번에 조회하고 검사
		List<Long> inventoryIds = request.usages().stream()
			.map(RecipeCompleteRequest.UsageDto::inventoryId)
			.toList();
		List<InventoryItem> inventoryItems = inventoryItemRepository.findAllById(inventoryIds);

		validateInventoryItems(userId, inventoryIds, inventoryItems);

		// 4. 재고마다 차감
		Short activeStatusId = getActiveStatusId();
		Short consumedStatusId = getConsumedStatusId();

		for (RecipeCompleteRequest.UsageDto usage : request.usages()) {
			InventoryItem item = inventoryItems.stream()
				.filter(i -> i.getId().equals(usage.inventoryId()))
				.findFirst()
				.orElseThrow();

			if (usage.usageRate() == 0) {
				// 0% → 변경 없음
				continue;
			}

			BigDecimal usedQuantity = item.getRemainingQuantity()
				.multiply(BigDecimal.valueOf(usage.usageRate()))
				.divide(BigDecimal.valueOf(100), 3, RoundingMode.HALF_UP);

			BigDecimal newRemainingQuantity = item.getRemainingQuantity().subtract(usedQuantity);

			if (newRemainingQuantity.compareTo(BigDecimal.ZERO) > 0) {
				// 부분 사용 → 새 메서드로 상태 유지
				item.decreaseRemainingQuantity(usedQuantity);
				inventoryItemRepository.save(item);
			} else {
				// 전체 사용 (≤ 0) → DisposeService로 consumed 처리
				UpdateInventoryItemStatusRequest disposeRequest = new UpdateInventoryItemStatusRequest(
					STATUS_CONSUMED, item.getRemainingQuantity(), null, null
				);
				disposeService.updateStatus(userId, item.getId(), disposeRequest);
			}
		}
	}

	private void validateNoDuplicateInventory(List<RecipeCompleteRequest.UsageDto> usages) {
		Set<Long> seenIds = new HashSet<>();
		for (RecipeCompleteRequest.UsageDto usage : usages) {
			if (!seenIds.add(usage.inventoryId())) {
				throw new BusinessException(ErrorCode.DUPLICATE_INVENTORY);
			}
		}
	}

	private void validateInventoryItems(Long userId, List<Long> inventoryIds, List<InventoryItem> inventoryItems) {
		// 조회된 재고가 요청한 개수와 일치하지 않으면 없는 것이 있음
		if (inventoryItems.size() != inventoryIds.size()) {
			throw new NotFoundException(ErrorCode.INVENTORY_NOT_FOUND.getDefaultMessage());
		}

		Short activeStatusId = getActiveStatusId();

		for (InventoryItem item : inventoryItems) {
			// 다른 사용자의 재고
			if (!item.getUserId().equals(userId)) {
				throw new BusinessException(ErrorCode.INVENTORY_ACCESS_DENIED);
			}

			// active 상태가 아님
			if (!item.getStatusId().equals(activeStatusId)) {
				throw new BusinessException(ErrorCode.INVENTORY_NOT_ACTIVE);
			}
		}
	}

	private Short getActiveStatusId() {
		return itemStatusRepository.findByCode("active")
			.map(status -> status.getId())
			.orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));
	}

	private Short getConsumedStatusId() {
		return itemStatusRepository.findByCode(STATUS_CONSUMED)
			.map(status -> status.getId())
			.orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));
	}
}
