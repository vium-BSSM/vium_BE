package com.vium.receipt.service;

import com.vium.global.code.PurchaseSourceRepository;
import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.inventory.dto.IngredientRegisterRequest;
import com.vium.inventory.dto.IngredientResponse;
import com.vium.inventory.service.InventoryService;
import com.vium.receipt.dto.PurchaseConfirmRequest;
import com.vium.receipt.dto.PurchaseConfirmResponse;
import com.vium.receipt.entity.PurchaseItem;
import com.vium.receipt.repository.PurchaseItemRepository;
import com.vium.receipt.repository.PurchaseRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PurchaseConfirmService {
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	private final PurchaseRepository purchases;
	private final PurchaseItemRepository items;
	private final PurchaseSourceRepository sources;
	private final InventoryService inventory;
	private final Clock clock;

	@Transactional
	public PurchaseConfirmResponse confirm(Long userId, Long purchaseId, PurchaseConfirmRequest request) {
		var purchase = purchases.findByIdAndUserIdForUpdate(purchaseId, userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.PURCHASE_NOT_FOUND));
		if (!purchase.isDraft()) throw new BusinessException(ErrorCode.PURCHASE_ALREADY_CONFIRMED);
		var now = clock.instant();
		if (request.purchasedAt().toInstant().isAfter(now)) throw new BusinessException(ErrorCode.INVALID_REQUEST);
		if (request.purchaseSourceId() != null && !sources.existsById(request.purchaseSourceId())) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, "존재하지 않는 purchaseSourceId 입니다");
		}
		var original = items.findAllByPurchaseIdOrderByIdAsc(purchaseId).stream()
			.collect(Collectors.toMap(PurchaseItem::getId, Function.identity()));
		for (var item : request.items()) {
			if (item.purchaseItemId() != null && !original.containsKey(item.purchaseItemId())) {
				throw new BusinessException(ErrorCode.INVALID_REQUEST, "이 구매 내역에 속하지 않는 품목입니다");
			}
		}
		items.deleteAllByPurchaseId(purchaseId);
		items.flush();
		var purchasedAt = request.purchasedAt().atZoneSameInstant(SEOUL).toLocalDateTime();
		var ingredients = new ArrayList<IngredientResponse>();
		BigDecimal total = null;
		for (var item : request.items()) {
			var old = item.purchaseItemId() == null ? null : original.get(item.purchaseItemId());
			var amount = item.amount() == null ? null : BigDecimal.valueOf(item.amount());
			var saved = items.save(PurchaseItem.builder().purchaseId(purchaseId)
				.ingredientCatalogId(item.ingredientCatalogId()).rawName(old == null ? item.customName() : old.getRawName())
				.quantity(item.quantity()).unitId(item.unitId()).storageMethodId(item.storageMethodId())
				.unitPrice(old == null ? null : old.getUnitPrice()).amount(amount).expiresOn(item.expiresOn()).build());
			var ingredient = inventory.registerFromPurchase(userId, new IngredientRegisterRequest(
				item.ingredientCatalogId(), item.customName(), item.quantity(), item.unitId(), item.storageMethodId(),
				purchasedAt.toLocalDate(), item.expiresOn(), item.amount()), saved.getId());
			saved.setExpiresOn(ingredient.expiresOn());
			ingredients.add(ingredient);
			if (amount != null) total = total == null ? amount : total.add(amount);
		}
		purchase.confirm(request.purchaseSourceId(), purchasedAt, total == null ? purchase.getTotalAmount() : total,
			LocalDateTime.ofInstant(now, SEOUL));
		return new PurchaseConfirmResponse(purchase.getId(), purchase.getStatus(), ingredients);
	}
}
