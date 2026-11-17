package com.vium.post.service;

import com.vium.ingredient.dto.CatalogPostInfo;
import com.vium.ingredient.service.IngredientCatalogService;
import com.vium.inventory.dto.IngredientDetailResponse;
import com.vium.inventory.service.InventoryLockChecker;
import com.vium.post.dto.PostIneligibleReason;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재고가 게시 가능한 상태인지 평가한다. post-preview와 등록 양쪽에서 같은 판정을 재사용한다.
 * 사유별 메시지는 호출부(PostRegisterService 등)에서 정한다 — 이 서비스는 사유 목록만 돌려준다.
 */
@Service
@RequiredArgsConstructor
public class PostEligibilityService {

	private static final String ACTIVE_STATUS_CODE = "active";
	private static final long MIN_DAYS_REMAINING = 1;

	private final @Qualifier("postInventoryLockChecker") InventoryLockChecker postInventoryLockChecker;
	private final IngredientCatalogService ingredientCatalogService;
	private final Clock clock;

	@Transactional(readOnly = true)
	public List<PostIneligibleReason> evaluate(IngredientDetailResponse detail) {
		List<PostIneligibleReason> reasons = new ArrayList<>();

		if (detail.remainingQuantity().compareTo(detail.initialQuantity()) != 0) {
			reasons.add(PostIneligibleReason.ALREADY_USED);
		}
		if (!ACTIVE_STATUS_CODE.equals(detail.statusCode())) {
			reasons.add(PostIneligibleReason.NOT_ACTIVE);
		}
		if (postInventoryLockChecker.isLocked(detail.inventoryItemId())) {
			reasons.add(PostIneligibleReason.ALREADY_POSTED);
		}
		if (daysRemaining(detail.expiresOn()) < MIN_DAYS_REMAINING) {
			reasons.add(PostIneligibleReason.EXPIRES_TOO_SOON);
		}
		if (detail.ingredientCatalogId() == null) {
			reasons.add(PostIneligibleReason.NOT_IN_CATALOG);
		} else if (!isPostAllowed(detail.ingredientCatalogId())) {
			reasons.add(PostIneligibleReason.PROHIBITED_ITEM);
		}

		return reasons;
	}

	private long daysRemaining(LocalDate expiresOn) {
		if (expiresOn == null) {
			return Long.MIN_VALUE;
		}
		return ChronoUnit.DAYS.between(LocalDate.now(clock), expiresOn);
	}

	private boolean isPostAllowed(Long ingredientCatalogId) {
		return ingredientCatalogService.findPostInfo(ingredientCatalogId)
			.map(CatalogPostInfo::postAllowed)
			.orElse(false);
	}
}
