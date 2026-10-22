package com.vium.receipt.service;

import com.vium.global.code.PurchaseSourceRepository;
import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.ingredient.dto.CatalogName;
import com.vium.ingredient.service.IngredientCatalogService;
import com.vium.receipt.dto.*;
import com.vium.receipt.entity.PurchaseStatus;
import com.vium.receipt.entity.PurchaseType;
import com.vium.receipt.repository.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PurchaseQueryService {
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	private final PurchaseRepository purchases;
	private final PurchaseItemRepository items;
	private final PurchaseQueryRepository queries;
	private final PurchaseSourceRepository sources;
	private final IngredientCatalogService catalog;

	@Transactional(readOnly = true)
	public PurchaseListResponse list(Long userId, PurchaseType type, PurchaseStatus status, LocalDate from, LocalDate to) {
		if (from != null && to != null && from.isAfter(to)) throw new BusinessException(ErrorCode.INVALID_REQUEST);
		if (LocalDate.MAX.equals(to)) throw new BusinessException(ErrorCode.INVALID_REQUEST);
		return new PurchaseListResponse(queries.findPurchases(userId, type, status,
			from == null ? null : from.atStartOfDay(), to == null ? null : to.plusDays(1).atStartOfDay()).stream()
			.map(row -> new PurchaseSummaryResponse(row.purchaseId(), row.type(), row.status(), row.purchaseSourceName(),
				row.purchasedAt().atZone(SEOUL).toOffsetDateTime(), won(row.totalAmount()), row.itemCount())).toList());
	}

	@Transactional(readOnly = true)
	public PurchaseDetailResponse detail(Long userId, Long purchaseId) {
		var purchase = purchases.findByIdAndUserId(purchaseId, userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.PURCHASE_NOT_FOUND));
		var found = items.findAllByPurchaseIdOrderByIdAsc(purchaseId);
		var ids = found.stream().map(item -> item.getIngredientCatalogId()).filter(Objects::nonNull).distinct().toList();
		var names = catalog.findNamesByIds(ids).stream().collect(Collectors.toMap(CatalogName::id, CatalogName::name));
		var source = purchase.getPurchaseSourceId() == null ? null : sources.findById(purchase.getPurchaseSourceId()).orElse(null);
		return new PurchaseDetailResponse(purchase.getId(), purchase.getType(), purchase.getStatus(), purchase.getImageId(),
			purchase.getPurchaseSourceId(), source == null ? null : source.getName(),
			purchase.getPurchasedAt().atZone(SEOUL).toOffsetDateTime(), won(purchase.getTotalAmount()),
			found.stream().map(item -> new PurchaseItemDraftResponse(item.getId(), item.getRawName(), item.getIngredientCatalogId(),
				names.get(item.getIngredientCatalogId()), item.getQuantity(), item.getUnitId(), item.getStorageMethodId(),
				won(item.getUnitPrice()), won(item.getAmount()), item.getExpiresOn())).toList(),
			purchase.getCreatedAt().atZone(SEOUL).toOffsetDateTime());
	}

	@Transactional
	public void delete(Long userId, Long purchaseId) {
		var purchase = purchases.findByIdAndUserIdForUpdate(purchaseId, userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.PURCHASE_NOT_FOUND));
		if (!purchase.isDraft()) throw new BusinessException(ErrorCode.PURCHASE_ALREADY_CONFIRMED);
		items.deleteAllByPurchaseId(purchaseId);
		items.flush();
		purchases.delete(purchase);
	}

	private Long won(BigDecimal amount) { return amount == null ? null : amount.longValueExact(); }
}
