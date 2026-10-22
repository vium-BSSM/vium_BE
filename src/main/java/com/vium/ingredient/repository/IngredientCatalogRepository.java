package com.vium.ingredient.repository;

import com.vium.ingredient.entity.IngredientCatalog;
import com.vium.ingredient.dto.CatalogName;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IngredientCatalogRepository extends JpaRepository<IngredientCatalog, Long> {

	@Query("SELECT new com.vium.ingredient.dto.CatalogName(c.id, c.name) FROM IngredientCatalog c")
	List<CatalogName> findAllNames();

	@Query(value = "SELECT id, category_id, default_unit_id, name, created_at FROM ingredient_catalog WHERE LOWER(name) LIKE LOWER(CONCAT('%', :keyword, '%')) ORDER BY name ASC LIMIT 20", nativeQuery = true)
	List<IngredientCatalog> searchByKeyword(@Param("keyword") String keyword);
}
