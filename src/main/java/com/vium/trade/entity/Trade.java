package com.vium.trade.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "trades")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Trade {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "post_id", nullable = false)
	private Long postId;

	@Column(name = "buyer_id", nullable = false)
	private Long buyerId;

	@Column(name = "seller_id", nullable = false)
	private Long sellerId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private TradeStatus status;

	@Column(nullable = false)
	private Integer price;

	@Column(name = "buyer_inventory_item_id")
	private Long buyerInventoryItemId;

	@Column(name = "canceled_by_id")
	private Long canceledById;

	@Column(name = "cancel_reason", length = 200)
	private String cancelReason;

	@Column(name = "requested_at", nullable = false)
	private LocalDateTime requestedAt;

	@Column(name = "accepted_at")
	private LocalDateTime acceptedAt;

	@Column(name = "auto_resolve_at")
	private LocalDateTime autoResolveAt;

	@Column(name = "completed_at")
	private LocalDateTime completedAt;

	@Column(name = "closed_at")
	private LocalDateTime closedAt;

	@Builder
	public Trade(Long postId, Long buyerId, Long sellerId, TradeStatus status, Integer price,
			Long buyerInventoryItemId, Long canceledById, String cancelReason, LocalDateTime requestedAt,
			LocalDateTime acceptedAt, LocalDateTime autoResolveAt, LocalDateTime completedAt,
			LocalDateTime closedAt) {
		this.postId = postId;
		this.buyerId = buyerId;
		this.sellerId = sellerId;
		this.status = status;
		this.price = price;
		this.buyerInventoryItemId = buyerInventoryItemId;
		this.canceledById = canceledById;
		this.cancelReason = cancelReason;
		this.requestedAt = requestedAt;
		this.acceptedAt = acceptedAt;
		this.autoResolveAt = autoResolveAt;
		this.completedAt = completedAt;
		this.closedAt = closedAt;
	}
}
