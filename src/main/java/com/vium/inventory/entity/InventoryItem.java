package com.vium.inventory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "inventory_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InventoryItem {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "ingredient_catalog_id")
	private Long ingredientCatalogId;

	@Column(name = "purchase_item_id")
	private Long purchaseItemId;

	@Column(name = "custom_name")
	private String customName;

	@Column(name = "status_id", nullable = false)
	private Short statusId;

	@Column(name = "storage_method_id")
	private Short storageMethodId;

	@Column(name = "unit_id", nullable = false)
	private Short unitId;

	@Column(name = "initial_quantity", nullable = false)
	private BigDecimal initialQuantity;

	@Column(name = "remaining_quantity", nullable = false)
	private BigDecimal remainingQuantity;

	private BigDecimal amount;

	@Column(name = "purchased_on")
	private LocalDate purchasedOn;

	@Column(name = "expires_on")
	private LocalDate expiresOn;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	@Builder
	public InventoryItem(Long userId, Long ingredientCatalogId, String customName, Short statusId,
			Short storageMethodId, Short unitId, BigDecimal initialQuantity, BigDecimal remainingQuantity,
			BigDecimal amount, LocalDate purchasedOn, LocalDate expiresOn) {
		this.userId = userId;
		this.ingredientCatalogId = ingredientCatalogId;
		this.customName = customName;
		this.statusId = statusId;
		this.storageMethodId = storageMethodId;
		this.unitId = unitId;
		this.initialQuantity = initialQuantity;
		this.remainingQuantity = remainingQuantity;
		this.amount = amount;
		this.purchasedOn = purchasedOn;
		this.expiresOn = expiresOn;
	}

	@PrePersist
	protected void onCreate() {
		LocalDateTime now = LocalDateTime.now();
		this.createdAt = now;
		this.updatedAt = now;
	}

	@PreUpdate
	protected void onUpdate() {
		this.updatedAt = LocalDateTime.now();
	}
}
