package com.vium.recipe.repository;

import com.vium.recipe.entity.Recipe;
import com.vium.recipe.entity.RecipeCategory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecipeRepository extends JpaRepository<Recipe, Long> {

	// 재사용 레시피 검색 (2-4의 4-a)
	// source = 'AI', 해당 category, 모든 재료가 사용자 보유 재료 안에 있음
	// (QueryDSL 또는 native query로 별도 구현)
	List<Recipe> findBySourceAndCategoryOrderByCreatedAtDesc(String source, RecipeCategory category);
}
