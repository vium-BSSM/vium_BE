package com.vium.recipe.repository;

import com.vium.recipe.entity.Recipe;
import com.vium.recipe.entity.RecipeCategory;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RecipeQueryRepositoryImpl implements RecipeQueryRepository {

	private final JdbcTemplate jdbcTemplate;
	private final RecipeRepository recipeRepository;

	private static final String FIND_REUSABLE_RECIPES_SQL =
		"SELECT r.id, r.title, r.description, r.category, r.cook_time, r.image_url, r.source, r.created_at "
			+ "FROM recipes r "
			+ "WHERE r.source = 'AI' "
			+ "  AND r.category = ? "
			+ "  AND NOT EXISTS ( "
			+ "    SELECT 1 "
			+ "    FROM recipe_ingredients ri "
			+ "    WHERE ri.recipe_id = r.id "
			+ "      AND ri.ingredient_catalog_id <> ALL (ARRAY[?]::bigint[]) "
			+ "  ) "
			+ "ORDER BY r.created_at DESC "
			+ "LIMIT 100";

	private static final RowMapper<Long> RECIPE_ID_MAPPER = (rs, rowNum) -> rs.getLong("id");

	@Override
	public List<Recipe> findReusableRecipes(RecipeCategory category, List<Long> userIngredientCatalogIds) {
		if (userIngredientCatalogIds == null || userIngredientCatalogIds.isEmpty()) {
			return List.of();
		}

		// PostgreSQL array casting
		String categoryStr = category.name();
		Long[] catalogIdArray = userIngredientCatalogIds.toArray(new Long[0]);

		List<Long> recipeIds = jdbcTemplate.query(
			FIND_REUSABLE_RECIPES_SQL,
			new Object[]{categoryStr, catalogIdArray},
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
}
