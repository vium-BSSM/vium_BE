package com.vium.inventory.service;

import com.vium.global.code.ItemStatus;
import com.vium.global.code.ItemStatusRepository;
import com.vium.global.code.StorageMethodRepository;
import com.vium.global.code.UnitRepository;
import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.global.exception.NotFoundException;
import com.vium.ingredient.service.ExpiryEstimationService;
import com.vium.ingredient.service.IngredientCatalogService;
import com.vium.inventory.dto.AutoDisposeCandidate;
import com.vium.inventory.dto.IngredientListResponse;
import com.vium.inventory.dto.IngredientRegisterRequest;
import com.vium.inventory.dto.IngredientResponse;
import com.vium.inventory.dto.IngredientUpdateRequest;
import com.vium.inventory.dto.IngredientUpdateResponse;
import com.vium.inventory.dto.InventoryItemSnapshot;
import com.vium.inventory.dto.InventoryState;
import com.vium.inventory.entity.InventoryItem;
import com.vium.inventory.repository.InventoryItemRepository;
import com.vium.inventory.repository.InventoryQueryRepository;
import com.vium.user.service.UserSettingsService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
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
	private final InventoryQueryRepository inventoryQueryRepository;
	private final UserSettingsService userSettingsService;

	@Transactional
	public IngredientUpdateResponse update(Long userId, Long inventoryItemId, IngredientUpdateRequest request) {
		InventoryItem item = inventoryItemRepository.findByIdAndUserId(inventoryItemId, userId)
			.orElseThrow(() -> new NotFoundException("식재료를 찾을 수 없습니다"));
		String customName = normalizeCustomName(request.customName());
		if (item.getIngredientCatalogId() == null && customName == null) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, "카탈로그에 없는 재료는 customName이 필요합니다");
		}
		if (!unitRepository.existsById(request.unitId())) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, "존재하지 않는 unitId 입니다");
		}
		if (!storageMethodRepository.existsById(request.storageMethodId())) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, "존재하지 않는 storageMethodId 입니다");
		}
		validateDates(request.purchasedOn(), request.expiresOn());
		ItemStatus status = itemStatusRepository.findById(item.getStatusId())
			.orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));
		boolean hasEvents = !item.getUnitId().equals(request.unitId())
			&& inventoryQueryRepository.hasConsumptionEvents(inventoryItemId, userId);
		item.updateDetails(customName, request.quantity(), request.unitId(), request.storageMethodId(),
			request.purchasedOn(), request.expiresOn(), hasEvents);
		return new IngredientUpdateResponse(item.getId(), item.getCustomName(), item.getInitialQuantity(),
			item.getUnitId(), item.getStorageMethodId(), item.getPurchasedOn(), item.getExpiresOn(), status.getCode());
	}

	@Transactional
	public InventoryState updateStatus(Long userId, Long inventoryItemId, BigDecimal quantity, Short statusId) {
		InventoryItem item = inventoryItemRepository.findByIdAndUserId(inventoryItemId, userId)
			.orElseThrow(() -> new NotFoundException("Inventory item not found"));
		item.updateStatus(quantity, statusId);
		inventoryItemRepository.save(item);
		return new InventoryState(item.getId(), item.getUserId(), item.getRemainingQuantity());
	}

	/**
	 * 소비/폐기 PATCH용 스냅샷. 행에 PESSIMISTIC_WRITE 락을 걸어 같은 트랜잭션이 끝날 때까지
	 * 다른 요청이 remainingQuantity를 읽거나 바꾸지 못하게 한다. 반드시 호출자 트랜잭션 안에서 사용한다.
	 */
	@Transactional
	public InventoryItemSnapshot getSnapshotForUpdate(Long userId, Long inventoryItemId) {
		InventoryItem item = inventoryItemRepository.findByIdAndUserIdForUpdate(inventoryItemId, userId)
			.orElseThrow(() -> new NotFoundException("Inventory item not found"));
		return new InventoryItemSnapshot(item.getId(), item.getUserId(), item.getRemainingQuantity(),
			item.getInitialQuantity(), item.getAmount());
	}

	@Transactional(readOnly = true)
	public InventoryItemSnapshot getSnapshot(Long userId, Long inventoryItemId) {
		InventoryItem item = inventoryItemRepository.findByIdAndUserId(inventoryItemId, userId)
			.orElseThrow(() -> new NotFoundException("Inventory item not found"));
		return new InventoryItemSnapshot(item.getId(), item.getUserId(), item.getRemainingQuantity(),
			item.getInitialQuantity(), item.getAmount());
	}

	@Transactional(readOnly = true)
	public List<AutoDisposeCandidate> findAutoDisposeCandidates(Short activeStatusId, LocalDate expiresBefore) {
		return inventoryItemRepository
			.findAllByStatusIdAndExpiresOnBeforeAndRemainingQuantityGreaterThan(
				activeStatusId, expiresBefore, BigDecimal.ZERO)
			.stream()
			.map(item -> new AutoDisposeCandidate(item.getId(), item.getUserId(), item.getRemainingQuantity(),
				item.getInitialQuantity(), item.getAmount(), item.getExpiresOn()))
			.toList();
	}

	/**
	 * 조건부 폐기 처리: status가 여전히 activeStatusId이고 remainingQuantity가 expectedRemainingQuantity와
	 * 일치할 때만 disposedStatusId로 변경하고 remainingQuantity를 0으로 설정한다.
	 * 동시 처리로 인한 경쟁 상황에서 중복 폐기를 방지하기 위한 조건부 UPDATE.
	 *
	 * @return 실제로 변경되었으면 true, 다른 프로세스가 이미 변경했으면 false
	 */
	@Transactional
	public boolean disposeIfUnchanged(Long inventoryItemId, Short activeStatusId, Short disposedStatusId,
			BigDecimal expectedRemainingQuantity, LocalDateTime updatedAt) {
		int affectedRows = inventoryItemRepository.disposeIfUnchanged(
			inventoryItemId, activeStatusId, disposedStatusId, expectedRemainingQuantity, updatedAt);
		return affectedRows == 1;
	}

	@Transactional(readOnly = true)
	public IngredientListResponse list(Long userId, boolean expiringSoon) {
		LocalDate expiresThrough = expiringSoon
			? LocalDate.now().plusDays(userSettingsService.getExpiryAlertDays(userId)) : null;
		return new IngredientListResponse(inventoryQueryRepository.findActiveIngredients(userId, expiresThrough));
	}

	@Transactional
	public IngredientResponse register(Long userId, IngredientRegisterRequest request) {
		return registerItem(userId, request, null);
	}

	@Transactional
	public IngredientResponse registerFromPurchase(Long userId, IngredientRegisterRequest request, Long purchaseItemId) {
		return registerItem(userId, request, purchaseItemId);
	}

	private IngredientResponse registerItem(Long userId, IngredientRegisterRequest request, Long purchaseItemId) {
		validate(request);

		LocalDate purchasedOn = request.purchasedOn() != null ? request.purchasedOn() : LocalDate.now();
		LocalDate expiresOn = resolveExpiresOn(request, purchasedOn);
		validateDates(purchasedOn, expiresOn);

		ItemStatus activeStatus = itemStatusRepository.findByCode(ACTIVE_STATUS_CODE)
			.orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "item_statuses 시드 데이터가 없습니다"));

		String customName = resolveCustomName(request.ingredientCatalogId(), request.customName());

		InventoryItem inventoryItem = InventoryItem.builder()
			.userId(userId)
			.purchaseItemId(purchaseItemId)
			.ingredientCatalogId(request.ingredientCatalogId())
			.customName(customName)
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
			throw new NotFoundException("존재하지 않는 ingredientCatalogId 입니다");
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

	private String resolveCustomName(Long ingredientCatalogId, String customName) {
		String normalized = normalizeCustomName(customName);
		if (normalized != null) {
			return normalized;
		}
		if (ingredientCatalogId != null) {
			return ingredientCatalogService.getNameById(ingredientCatalogId);
		}
		return null;
	}
}
