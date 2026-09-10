package com.vium.inventory.service;

import com.vium.global.code.ItemStatus;
import com.vium.global.code.ItemStatusRepository;
import com.vium.global.code.StorageMethodRepository;
import com.vium.global.code.UnitRepository;
import com.vium.global.common.BusinessException;
import com.vium.global.common.ErrorCode;
import com.vium.ingredient.service.ExpiryEstimationService;
import com.vium.ingredient.service.IngredientCatalogService;
import com.vium.inventory.dto.IngredientRegisterRequest;
import com.vium.inventory.dto.IngredientResponse;
import com.vium.inventory.entity.InventoryItem;
import com.vium.inventory.repository.InventoryItemRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class InventoryService {

	private static final String ACTIVE_STATUS_CODE = "active";

	private final InventoryItemRepository inventoryItemRepository;
	private final IngredientCatalogService ingredientCatalogService;
	private final UnitRepository unitRepository;
	private final StorageMethodRepository storageMethodRepository;
	private final ItemStatusRepository itemStatusRepository;
	private final ExpiryEstimationService expiryEstimationService;

	@Transactional
	public IngredientResponse register(Long userId, IngredientRegisterRequest request) {
		validate(request);

		LocalDate purchasedOn = request.purchasedOn() != null ? request.purchasedOn() : LocalDate.now();
		LocalDate expiresOn = resolveExpiresOn(request, purchasedOn);
		validateDates(purchasedOn, expiresOn);

		ItemStatus activeStatus = itemStatusRepository.findByCode(ACTIVE_STATUS_CODE)
			.orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "item_statuses 시드 데이터가 없습니다"));

		InventoryItem inventoryItem = InventoryItem.builder()
			.userId(userId)
			.ingredientCatalogId(request.ingredientCatalogId())
			.customName(normalizeCustomName(request.customName()))
			.statusId(activeStatus.getId())
			.storageMethodId(request.storageMethodId())
			.unitId(request.unitId())
			.initialQuantity(request.quantity())
			.remainingQuantity(request.quantity())
			.amount(request.amount() != null ? BigDecimal.valueOf(request.amount()) : null)
			.purchasedOn(purchasedOn)
			.expiresOn(expiresOn)
			.build();

		InventoryItem saved = inventoryItemRepository.save(inventoryItem);

		return new IngredientResponse(
			saved.getId(),
			saved.getIngredientCatalogId(),
			saved.getCustomName(),
			saved.getInitialQuantity(),
			saved.getRemainingQuantity(),
			saved.getUnitId(),
			saved.getStorageMethodId(),
			activeStatus.getCode(),
			saved.getPurchasedOn(),
			saved.getExpiresOn());
	}

	private void validate(IngredientRegisterRequest request) {
		if (request.ingredientCatalogId() == null && !StringUtils.hasText(request.customName())) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, "ingredientCatalogId 또는 customName 중 하나는 필요합니다");
		}
		if (request.ingredientCatalogId() != null && !ingredientCatalogService.existsById(request.ingredientCatalogId())) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, "존재하지 않는 ingredientCatalogId 입니다");
		}
		if (!unitRepository.existsById(request.unitId())) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, "존재하지 않는 unitId 입니다");
		}
		if (request.storageMethodId() != null && !storageMethodRepository.existsById(request.storageMethodId())) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, "존재하지 않는 storageMethodId 입니다");
		}
	}

	private LocalDate resolveExpiresOn(IngredientRegisterRequest request, LocalDate purchasedOn) {
		if (request.expiresOn() != null) {
			return request.expiresOn();
		}

		return expiryEstimationService
			.estimateExpiresOn(request.ingredientCatalogId(), request.storageMethodId(), purchasedOn)
			.orElseThrow(() -> new BusinessException(
				ErrorCode.INVALID_REQUEST,
				"소비기한을 추정할 수 없습니다. expiresOn을 입력해 주세요"));
	}

	private void validateDates(LocalDate purchasedOn, LocalDate expiresOn) {
		if (expiresOn.isBefore(purchasedOn)) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, "expiresOn은 purchasedOn보다 빠를 수 없습니다");
		}
	}

	private String normalizeCustomName(String customName) {
		return StringUtils.hasText(customName) ? customName.trim() : null;
	}
}
