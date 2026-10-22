package com.vium.receipt.entity;

import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.math.BigDecimal;
import java.time.LocalDateTime;
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
@Table(name = "purchases")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Purchase {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "purchase_source_id")
	private Short purchaseSourceId;

	@Enumerated(EnumType.STRING)
	@Column(name = "type", nullable = false, length = 20)
	private PurchaseType type;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private PurchaseStatus status;

	@Column(name = "image_id")
	private Long imageId;

	@Column(name = "total_amount", precision = 12, scale = 2)
	private BigDecimal totalAmount;

	@Column(name = "purchased_at", nullable = false)
	private LocalDateTime purchasedAt;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "confirmed_at")
	private LocalDateTime confirmedAt;

	public static Purchase draft(Long userId, PurchaseType type, Long imageId,
			Short purchaseSourceId, BigDecimal totalAmount, LocalDateTime purchasedAt, LocalDateTime now) {
		var purchase = new Purchase();
		purchase.userId = userId;
		purchase.type = type;
		purchase.status = PurchaseStatus.DRAFT;
		purchase.imageId = imageId;
		purchase.purchaseSourceId = purchaseSourceId;
		purchase.totalAmount = totalAmount;
		purchase.purchasedAt = purchasedAt;
		purchase.createdAt = now;
		return purchase;
	}

	public void confirm(Short purchaseSourceId, LocalDateTime purchasedAt,
			BigDecimal totalAmount, LocalDateTime now) {
		if (!isDraft()) {
			throw new BusinessException(ErrorCode.PURCHASE_ALREADY_CONFIRMED);
		}
		this.purchaseSourceId = purchaseSourceId;
		this.purchasedAt = purchasedAt;
		this.totalAmount = totalAmount;
		this.status = PurchaseStatus.CONFIRMED;
		this.confirmedAt = now;
	}

	public boolean isDraft() {
		return status == PurchaseStatus.DRAFT;
	}
}
