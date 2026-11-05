package com.vium.recipe.repository;

import com.vium.recipe.entity.Recipe;
import com.vium.recipe.entity.RecipeCategory;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface RecipeRepository extends JpaRepository<Recipe, Long> {

	// 재사용 레시피 검색 (2-4의 4-a)
	// source = 'AI', 해당 category, 모든 재료가 사용자 보유 재료 안에 있음
	// (QueryDSL 또는 native query로 별도 구현)
	List<Recipe> findBySourceAndCategoryOrderByCreatedAtDesc(String source, RecipeCategory category);

	/**
	 * 레시피 상세(분량+단계) 보강 작업을 원자적으로 선점한다.
	 * BASIC 상태면서 마지막 시도로부터 retryCooldownBefore 이전이거나(또는 시도한 적 없거나),
	 * GENERATING 상태로 staleBefore 이전에 시작돼 멈춰 있는 경우에만 선점에 성공한다.
	 * 두 기준을 분리한 이유: BASIC 쪽은 "방금 실패한 레시피를 얼마나 빨리 재시도할지"(정책),
	 * GENERATING 쪽은 "LLM 호출이 실제로 아직 살아있는지"(타임아웃에 종속)로 성격이 다르다.
	 *
	 * @return 선점에 성공하면 1, 이미 다른 요청이 처리 중/쿨다운 중이면 0
	 */
	@Modifying(clearAutomatically = true)
	@Transactional
	@Query(value = """
		UPDATE recipes
		SET detail_status = 'GENERATING', detail_generation_started_at = :now
		WHERE id = :id
		  AND (
		    (detail_status = 'BASIC' AND (detail_generation_started_at IS NULL OR detail_generation_started_at < :retryCooldownBefore))
		    OR (detail_status = 'GENERATING' AND detail_generation_started_at < :staleBefore)
		  )
		""", nativeQuery = true)
	int claimDetailGeneration(@Param("id") Long id, @Param("now") Instant now,
		@Param("retryCooldownBefore") Instant retryCooldownBefore, @Param("staleBefore") Instant staleBefore);
}
