package com.vium.receipt.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Builder;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "purchase_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PurchaseItem {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "purchase_id", nullable = false)
	private Long purchaseId;

	@Column(name = "ingredient_catalog_id")
	private Long ingredientCatalogId;

	@Column(name = "raw_name", length = 200)
	private String rawName;

	@Column(name = "quantity", nullable = false, precision = 12, scale = 3)
	private BigDecimal quantity;

	@Column(name = "unit_id", nullable = false)
	private Short unitId;

	@Column(name = "storage_method_id")
	private Short storageMethodId;

	@Column(name = "unit_price", precision = 12, scale = 2)
	private BigDecimal unitPrice;

	@Column(name = "amount", precision = 12, scale = 2)
	private BigDecimal amount;

	@Column(name = "expires_on")
	private LocalDate expiresOn;

	@Builder
	public PurchaseItem(Long purchaseId, Long ingredientCatalogId, String rawName,
			BigDecimal quantity, Short unitId, Short storageMethodId, BigDecimal unitPrice,
			BigDecimal amount, LocalDate expiresOn) {
		this.purchaseId = purchaseId;
		this.ingredientCatalogId = ingredientCatalogId;
		this.rawName = rawName;
		this.quantity = quantity;
		this.unitId = unitId;
		this.storageMethodId = storageMethodId;
		this.unitPrice = unitPrice;
		this.amount = amount;
		this.expiresOn = expiresOn;
	}
}
