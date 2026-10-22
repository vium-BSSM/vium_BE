package com.vium.recipe.service;

import com.vium.dispose.service.DisposeService;
import com.vium.dispose.dto.UpdateInventoryItemStatusRequest;
import com.vium.global.code.ItemStatusRepository;
import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.global.exception.InvalidRequestException;
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

		// 1-1. usageRate / usedQuantity 중 하나만 보냈는지, usedQuantity 소수 자릿수 검사
		validateUsageValues(request.usages());

		// 2. 레시피 접근 권한 확인
		if (!recipeSuggestionRepository.existsByUserIdAndRecipeId(userId, recipeId)) {
			throw new BusinessException(ErrorCode.RECIPE_NOT_FOUND);
		}

		// 3. 모든 재고를 한 번에 조회하고 검사
		List<Long> inventoryIds = request.usages().stream()
			.map(RecipeCompleteRequest.UsageDto::inventoryId)
			.toList();
		List<InventoryItem> inventoryItems = inventoryItemRepository.findAllById(inventoryIds);

		validateInventoryItems(userId, inventoryIds, inventoryItems);
		validateUsedQuantities(request.usages(), inventoryItems);

		// 4. 재고마다 차감
		Short activeStatusId = getActiveStatusId();
		Short consumedStatusId = getConsumedStatusId();

		for (RecipeCompleteRequest.UsageDto usage : request.usages()) {
			InventoryItem item = inventoryItems.stream()
				.filter(i -> i.getId().equals(usage.inventoryId()))
				.findFirst()
				.orElseThrow();

			BigDecimal usedQuantity;
			if (usage.usedQuantity() != null) {
				if (usage.usedQuantity().signum() == 0) {
					// 사용 수량 0 → 변경 없음
					continue;
				}
				usedQuantity = usage.usedQuantity();
			} else {
				if (usage.usageRate() == 0) {
					// 0% → 변경 없음
					continue;
				}

				usedQuantity = item.getRemainingQuantity()
					.multiply(BigDecimal.valueOf(usage.usageRate()))
					.divide(BigDecimal.valueOf(100), 3, RoundingMode.HALF_UP);
			}

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

	private void validateUsageValues(List<RecipeCompleteRequest.UsageDto> usages) {
		for (RecipeCompleteRequest.UsageDto usage : usages) {
			if (usage.usageRate() != null && usage.usedQuantity() != null) {
				throw new InvalidRequestException("usageRate와 usedQuantity는 함께 보낼 수 없습니다.");
			}
			if (usage.usageRate() == null && usage.usedQuantity() == null) {
				throw new InvalidRequestException("usageRate 또는 usedQuantity 중 하나는 필요합니다.");
			}
			if (usage.usedQuantity() != null && usage.usedQuantity().signum() < 0) {
				throw new InvalidRequestException("usedQuantity는 0 이상이어야 합니다.");
			}
			if (usage.usedQuantity() != null && usage.usedQuantity().scale() > 3) {
				throw new InvalidRequestException("usedQuantity는 소수점 3자리까지 입력할 수 있습니다.");
			}
		}
	}

	private void validateUsedQuantities(List<RecipeCompleteRequest.UsageDto> usages,
		List<InventoryItem> inventoryItems) {
		for (RecipeCompleteRequest.UsageDto usage : usages) {
			if (usage.usedQuantity() == null) {
				continue;
			}
			InventoryItem item = inventoryItems.stream()
				.filter(i -> i.getId().equals(usage.inventoryId()))
				.findFirst()
				.orElseThrow();
			if (usage.usedQuantity().compareTo(item.getRemainingQuantity()) > 0) {
				throw new InvalidRequestException("usedQuantity는 남은 수량을 초과할 수 없습니다.");
			}
		}
	}

	private void validateInventoryItems(Long userId, List<Long> inventoryIds, List<InventoryItem> inventoryItems) {
		// 조회된 재고가 요청한 개수와 일치하지 않으면 없는 것이 있음
		if (inventoryItems.size() != inventoryIds.size()) {
			throw new BusinessException(ErrorCode.INVENTORY_NOT_FOUND);
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
