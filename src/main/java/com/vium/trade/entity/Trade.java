package com.vium.trade.entity;

import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
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
	@Column(name = "buyer_inventory_item_id", unique = true)
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
	@Column(name = "auto_complete_notified_at")
	private LocalDateTime autoCompleteNotifiedAt;

	public static Trade request(Long postId, Long buyerId, Long sellerId, Integer price, LocalDateTime now) {
		Trade trade = new Trade();
		trade.postId = postId;
		trade.buyerId = buyerId;
		trade.sellerId = sellerId;
		trade.price = price;
		trade.status = TradeStatus.REQUESTED;
		trade.requestedAt = now;
		return trade;
	}

	public void requireStatus(TradeStatus expected) {
		if (status != expected) throw new BusinessException(ErrorCode.INVALID_TRADE_STATUS);
	}

	public void accept(LocalDateTime now) {
		requireStatus(TradeStatus.REQUESTED);
		status = TradeStatus.ACCEPTED;
		acceptedAt = now;
		autoResolveAt = now.plusHours(72);
	}

	public void reject(LocalDateTime now) {
		requireStatus(TradeStatus.REQUESTED);
		status = TradeStatus.REJECTED;
		closedAt = now;
	}

	public void cancel(Long canceledById, String reason, LocalDateTime now) {
		if (status != TradeStatus.REQUESTED && status != TradeStatus.ACCEPTED) {
			throw new BusinessException(ErrorCode.INVALID_TRADE_STATUS);
		}
		status = TradeStatus.CANCELED;
		this.canceledById = canceledById;
		cancelReason = reason;
		closedAt = now;
	}

	public void complete(Long buyerInventoryItemId, LocalDateTime now) {
		requireStatus(TradeStatus.ACCEPTED);
		status = TradeStatus.COMPLETED;
		this.buyerInventoryItemId = buyerInventoryItemId;
		completedAt = now;
		closedAt = now;
	}

	public void markAutoCompleteNotified(LocalDateTime now) {
		requireStatus(TradeStatus.ACCEPTED);
		if (autoCompleteNotifiedAt == null) autoCompleteNotifiedAt = now;
	}

	public boolean isBuyer(Long userId) { return buyerId.equals(userId); }
	public boolean isSeller(Long userId) { return sellerId.equals(userId); }
	public boolean isParticipant(Long userId) { return isBuyer(userId) || isSeller(userId); }
	public Long partnerOf(Long userId) {
		if (!isParticipant(userId)) throw new BusinessException(ErrorCode.TRADE_NOT_FOUND);
		return isBuyer(userId) ? sellerId : buyerId;
	}
	public int heldCredit() {
		return status == TradeStatus.REQUESTED || status == TradeStatus.ACCEPTED ? price : 0;
	}
}
