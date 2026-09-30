package com.vium.recipe.repository;

import com.vium.recipe.entity.RecipeStep;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecipeStepRepository extends JpaRepository<RecipeStep, Long> {
	boolean existsByRecipeId(Long recipeId);

	List<RecipeStep> findByRecipeIdOrderByStepOrderAsc(Long recipeId);
}
