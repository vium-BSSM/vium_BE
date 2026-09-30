package com.vium.recipe.repository;

import com.vium.recipe.entity.RecipeIngredient;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecipeIngredientRepository extends JpaRepository<RecipeIngredient, Long> {

	List<RecipeIngredient> findByRecipeIdOrderByIdAsc(Long recipeId);

	List<RecipeIngredient> findByRecipeId(Long recipeId);
}
