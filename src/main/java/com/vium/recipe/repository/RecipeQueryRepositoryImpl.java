package com.vium.recipe.repository;

import com.vium.recipe.entity.Recipe;
import com.vium.recipe.entity.RecipeCategory;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RecipeQueryRepositoryImpl implements RecipeQueryRepository {

	private final NamedParameterJdbcTemplate jdbcTemplate;
	private final RecipeRepository recipeRepository;

	private static final String FIND_REUSABLE_RECIPES_SQL =
		"SELECT r.id, r.title, r.description, r.category, r.cook_time, r.image_url, r.source, r.created_at "
			+ "FROM recipes r "
			+ "WHERE r.source = 'AI' "
			+ "  AND r.category = :category "
			+ "  AND EXISTS (SELECT 1 FROM recipe_ingredients ri WHERE ri.recipe_id = r.id) "
			+ "  AND EXISTS (SELECT 1 FROM recipe_steps rs WHERE rs.recipe_id = r.id) "
			+ "  AND NOT EXISTS ( "
			+ "    SELECT 1 "
			+ "    FROM recipe_ingredients ri "
			+ "    WHERE ri.recipe_id = r.id "
			+ "      AND ri.ingredient_catalog_id NOT IN (:catalogIds) "
			+ "  ) "
			+ "ORDER BY r.created_at DESC "
			+ "LIMIT 100";

	private static final RowMapper<Long> RECIPE_ID_MAPPER = (rs, rowNum) -> rs.getLong("id");

	@Override
	public List<Recipe> findReusableRecipes(RecipeCategory category, List<Long> userIngredientCatalogIds) {
		if (userIngredientCatalogIds == null || userIngredientCatalogIds.isEmpty()) {
			return List.of();
		}

		List<Long> recipeIds = jdbcTemplate.query(
			FIND_REUSABLE_RECIPES_SQL,
			Map.of("category", category.name(), "catalogIds", userIngredientCatalogIds),
			RECIPE_ID_MAPPER
		);

		if (recipeIds.isEmpty()) {
			return List.of();
		}

		return recipeIds.stream()
			.map(recipeRepository::findById)
			.filter(java.util.Optional::isPresent)
			.map(java.util.Optional::get)
			.toList();
	}

	@Override
	public boolean hasCompleteDetails(List<Long> recipeIds) {
		if (recipeIds.isEmpty()) {
			return false;
		}
		Long count = jdbcTemplate.queryForObject("""
			SELECT COUNT(*) FROM recipes r
			WHERE r.id IN (:recipeIds)
			AND EXISTS (SELECT 1 FROM recipe_ingredients ri WHERE ri.recipe_id = r.id)
			AND EXISTS (SELECT 1 FROM recipe_steps rs WHERE rs.recipe_id = r.id)
			""", Map.of("recipeIds", recipeIds), Long.class);
		return count != null && count == recipeIds.stream().distinct().count();
	}
}
