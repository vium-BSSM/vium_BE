package com.vium.receipt.service;

import com.vium.global.code.PurchaseSourceRepository;
import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.image.service.ImageService;
import com.vium.ingredient.dto.CatalogName;
import com.vium.ingredient.service.ExpiryEstimationService;
import com.vium.ingredient.service.IngredientCatalogService;
import com.vium.receipt.dto.PurchaseDetailResponse;
import com.vium.receipt.dto.PurchaseItemDraftResponse;
import com.vium.receipt.dto.ScanRequest;
import com.vium.receipt.entity.Purchase;
import com.vium.receipt.entity.PurchaseItem;
import com.vium.receipt.entity.PurchaseType;
import com.vium.receipt.parser.OcrRowAssembler;
import com.vium.receipt.parser.OnlineOrderTextParser;
import com.vium.receipt.parser.ReceiptTextParser;
import com.vium.receipt.repository.PurchaseItemRepository;
import com.vium.receipt.repository.PurchaseRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PurchaseScanService {
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	private static final List<Short> STORAGE_ORDER = List.of((short) 1, (short) 3, (short) 2);
	private final ImageService images;
	private final IngredientCatalogService catalog;
	private final ExpiryEstimationService expiry;
	private final PurchaseSourceRepository sources;
	private final PurchaseRepository purchases;
	private final PurchaseItemRepository purchaseItems;
	private final Clock clock;

	@Transactional
	public PurchaseDetailResponse scan(Long userId, PurchaseType type, ScanRequest request) {
		for (var line : request.lines()) {
			if (line.left() > line.right() || line.top() > line.bottom()) {
				throw new BusinessException(ErrorCode.INVALID_REQUEST, "인식 결과의 좌표가 올바르지 않습니다.");
			}
		}
		if (request.imageId() != null) images.requireReceiptImage(userId, request.imageId());
		var scanClock = Clock.fixed(clock.instant(), SEOUL);
		var now = LocalDateTime.now(scanClock);
		var rows = new OcrRowAssembler().assemble(request.lines());
		var parsed = type == PurchaseType.RECEIPT
			? new ReceiptTextParser(scanClock).parse(rows) : new OnlineOrderTextParser(scanClock).parse(rows);
		var normalized = new PurchaseScanNormalizer().normalize(parsed, now);
		var names = catalog.findAllNames().stream()
			.sorted(Comparator.<CatalogName>comparingInt(name -> normalizeName(name.name()).length())
				.reversed().thenComparing(CatalogName::id)).toList();
		var source = sources.findByCode(normalized.purchaseSourceCode()).orElse(null);
		var purchase = purchases.save(Purchase.draft(userId, type, request.imageId(),
			source == null ? null : source.getId(), normalized.totalAmount(), normalized.purchasedAt(), now));
		var responses = new ArrayList<PurchaseItemDraftResponse>();
		for (var item : normalized.items()) {
			var matched = match(item.rawName(), names);
			Long catalogId = matched == null ? null : matched.id();
			Short storageId = 1;
			LocalDate expiresOn = null;
			if (catalogId != null) {
				for (Short candidate : STORAGE_ORDER) {
					var date = expiry.estimateExpiresOn(catalogId, candidate, normalized.purchasedAt().toLocalDate());
					if (date.isPresent()) {
						storageId = candidate;
						expiresOn = date.get();
						break;
					}
				}
			}
			var saved = purchaseItems.save(PurchaseItem.builder().purchaseId(purchase.getId())
				.ingredientCatalogId(catalogId).rawName(item.rawName()).quantity(item.quantity())
				.unitId(item.unitId()).storageMethodId(storageId).unitPrice(item.unitPrice())
				.amount(item.amount()).expiresOn(expiresOn).build());
			responses.add(new PurchaseItemDraftResponse(saved.getId(), saved.getRawName(), catalogId,
				matched == null ? null : matched.name(), saved.getQuantity(), saved.getUnitId(), storageId,
				won(saved.getUnitPrice()), won(saved.getAmount()), expiresOn));
		}
		return new PurchaseDetailResponse(purchase.getId(), purchase.getType(), purchase.getStatus(),
			purchase.getImageId(), purchase.getPurchaseSourceId(), source == null ? null : source.getName(),
			purchase.getPurchasedAt().atZone(SEOUL).toOffsetDateTime(), won(purchase.getTotalAmount()), responses,
			purchase.getCreatedAt().atZone(SEOUL).toOffsetDateTime());
	}

	private CatalogName match(String rawName, List<CatalogName> names) {
		String normalized = normalizeName(rawName);
		var tokens = Arrays.stream(rawName.strip().split("\\s+"))
			.map(token -> token.toLowerCase(Locale.ROOT)).toList();
		for (var candidate : names) {
			String name = normalizeName(candidate.name());
			if (name.isEmpty()) continue;
			if (name.length() == 1 ? tokens.contains(name) : normalized.contains(name)) return candidate;
		}
		return null;
	}

	private String normalizeName(String name) {
		return name.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
	}

	private Long won(BigDecimal amount) {
		return amount == null ? null : amount.longValueExact();
	}
}
