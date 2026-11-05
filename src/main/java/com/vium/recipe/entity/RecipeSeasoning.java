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
@Table(name = "recipe_seasonings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecipeSeasoning {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "recipe_id", nullable = false)
	private Long recipeId;

	@Column(name = "name", nullable = false, length = 30)
	private String name;

	@Column(name = "amount", nullable = false, length = 50)
	private String amount;

	@Column(name = "sort_order", nullable = false)
	private Integer sortOrder;

	@Builder
	public RecipeSeasoning(Long recipeId, String name, String amount, Integer sortOrder) {
		this.recipeId = recipeId;
		this.name = name;
		this.amount = amount;
		this.sortOrder = sortOrder;
	}
}
