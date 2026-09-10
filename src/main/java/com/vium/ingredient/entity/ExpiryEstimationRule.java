package com.vium.ingredient.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "expiry_estimation_rules")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExpiryEstimationRule {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "ingredient_catalog_id", nullable = false)
	private Long ingredientCatalogId;

	@Column(name = "storage_method_id", nullable = false)
	private Short storageMethodId;

	@Column(name = "shelf_life_days", nullable = false)
	private Integer shelfLifeDays;

	private String source;

	@Column(name = "effective_from", nullable = false)
	private LocalDate effectiveFrom;

	@Column(name = "effective_to")
	private LocalDate effectiveTo;
}
