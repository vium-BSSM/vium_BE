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
@Table(name = "recipe_steps")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecipeStep {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "recipe_id", nullable = false)
	private Long recipeId;

	@Column(name = "step_order", nullable = false)
	private Integer stepOrder;

	@Column(name = "description", nullable = false, length = 500)
	private String description;

	@Builder
	public RecipeStep(Long recipeId, Integer stepOrder, String description) {
		this.recipeId = recipeId;
		this.stepOrder = stepOrder;
		this.description = description;
	}
}
