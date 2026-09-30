package com.vium.recipe.repository;

import com.vium.recipe.entity.Recipe;
import com.vium.recipe.entity.RecipeCategory;
import java.util.List;

public interface RecipeQueryRepository {

	// 재사용 레시피 검색 (2-4의 4-a)
	// source = 'AI', 해당 category
	// 모든 재료가 사용자 보유 재료 안에 있는 레시피만 조회
	// 반환: 최대 100개 (점수 순 정렬은 서비스에서 수행)
	List<Recipe> findReusableRecipes(RecipeCategory category, List<Long> userIngredientCatalogIds);
}
