package com.vium.recipe.repository;

import com.vium.recipe.entity.RecipeSuggestion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecipeSuggestionRepository extends JpaRepository<RecipeSuggestion, Long> {

	// 최근 추천 묶음 조회 (3-4의 캐시 확인, 2-4의 3)
	Optional<RecipeSuggestion> findFirstByUserIdOrderBySuggestedAtDesc(Long userId);

	// 사용자의 특정 배치 레시피 조회
	List<RecipeSuggestion> findByUserIdAndBatchIdOrderByRecipeIdAsc(Long userId, UUID batchId);

	// 사용자가 추천받은 레시피 확인 (3-4의 1, 5-4의 2)
	boolean existsByUserIdAndRecipeId(Long userId, Long recipeId);
}
