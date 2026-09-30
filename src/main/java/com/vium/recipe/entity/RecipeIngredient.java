package com.vium.recipe.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "recipe_ingredients")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecipeIngredient {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "recipe_id", nullable = false)
	private Long recipeId;

	@Column(name = "ingredient_catalog_id", nullable = false)
	private Long ingredientCatalogId;

	@Builder
	public RecipeIngredient(Long recipeId, Long ingredientCatalogId) {
		this.recipeId = recipeId;
		this.ingredientCatalogId = ingredientCatalogId;
	}
}
