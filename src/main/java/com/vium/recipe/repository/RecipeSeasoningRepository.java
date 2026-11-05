package com.vium.recipe.repository;

import com.vium.recipe.entity.RecipeSeasoning;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecipeSeasoningRepository extends JpaRepository<RecipeSeasoning, Long> {

	List<RecipeSeasoning> findByRecipeIdOrderBySortOrderAsc(Long recipeId);

	void deleteByRecipeId(Long recipeId);
}
