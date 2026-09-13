package com.vium.inventory.repository;

import com.vium.inventory.dto.IngredientListResponse;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class InventoryQueryRepository {

	private final JdbcTemplate jdbcTemplate;

	public List<IngredientListResponse.Item> findActiveIngredients(Long userId, LocalDate expiresThrough) {
		String sql = """
			select i.id, i.ingredient_catalog_id,
			       coalesce(c.name, i.custom_name) as name, category.name as category_name,
			       i.initial_quantity, i.remaining_quantity, i.unit_id, u.name as unit,
			       s.code as status_code, i.purchased_on, i.expires_on
			from inventory_items i
			join item_statuses s on s.id = i.status_id
			join units u on u.id = i.unit_id
			left join ingredient_catalog c on c.id = i.ingredient_catalog_id
			left join ingredient_categories category on category.id = c.category_id
			where i.user_id = ? and s.code = ?
			""";
		Object[] parameters = {userId, "active"};
		if (expiresThrough != null) {
			sql += " and i.expires_on <= ?";
			parameters = new Object[] {userId, "active", expiresThrough};
		}
		sql += " order by i.expires_on asc nulls last, i.id asc";
		return jdbcTemplate.query(sql, (rs, rowNum) -> new IngredientListResponse.Item(
			rs.getLong("id"),
			rs.getObject("ingredient_catalog_id", Long.class),
			rs.getString("name"),
			rs.getString("category_name"),
			rs.getBigDecimal("initial_quantity"),
			rs.getBigDecimal("remaining_quantity"),
			rs.getShort("unit_id"),
			rs.getString("unit"),
			rs.getString("status_code"),
			rs.getObject("purchased_on", LocalDate.class),
			rs.getObject("expires_on", LocalDate.class)), parameters);
	}
}
