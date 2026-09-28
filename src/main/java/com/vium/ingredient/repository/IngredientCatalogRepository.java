package com.vium.ingredient.repository;

import com.vium.ingredient.entity.IngredientCatalog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngredientCatalogRepository extends JpaRepository<IngredientCatalog, Long> {
}
