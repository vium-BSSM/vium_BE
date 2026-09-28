package com.vium.ingredient.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "ingredient_catalog")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IngredientCatalog {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "category_id")
	private Short categoryId;

	@Column(name = "default_unit_id", nullable = false)
	private Short defaultUnitId;

	private String name;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;
}
