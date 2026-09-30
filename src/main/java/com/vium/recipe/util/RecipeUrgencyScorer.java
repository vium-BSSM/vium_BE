package com.vium.recipe.util;

import com.vium.inventory.entity.InventoryItem;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RecipeUrgencyScorer {

	/**
	 * 레시피의 긴급도 점수를 계산합니다.
	 * 점수 = 각 재료의 가중치 합
	 *
	 * @param ingredientCatalogIds 레시피에 사용되는 재료 카탈로그 ID 목록
	 * @param userInventory 사용자의 재고 목록 (catalogId → 가장 빠른 소비기한)
	 * @return 긴급도 점수 (0 이상)
	 */
	public int calculateScore(List<Long> ingredientCatalogIds,
		Map<Long, LocalDate> userInventory) {
		LocalDate today = LocalDate.now();
		int totalScore = 0;

		for (Long catalogId : ingredientCatalogIds) {
			LocalDate expiresOn = userInventory.get(catalogId);
			int weight = calculateWeight(expiresOn, today);
			totalScore += weight;
		}

		return totalScore;
	}

	private int calculateWeight(LocalDate expiresOn, LocalDate today) {
		if (expiresOn == null) {
			return 0; // 소비기한 없음
		}

		long daysUntilExpiry = java.time.temporal.ChronoUnit.DAYS.between(today, expiresOn);

		if (daysUntilExpiry <= 1) {
			return 3; // D-1 이하
		} else if (daysUntilExpiry <= 3) {
			return 2; // D-2, D-3
		} else if (daysUntilExpiry <= 7) {
			return 1; // D-4 ~ D-7
		} else {
			return 0; // D-8 이상
		}
	}
}
