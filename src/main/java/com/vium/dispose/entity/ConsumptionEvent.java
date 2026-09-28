package com.vium.dispose.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "consumption_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConsumptionEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "inventory_item_id", nullable = false)
	private Long inventoryItemId;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "event_type", nullable = false, length = 20)
	private String eventType;

	@Column(name = "event_source_id", nullable = false)
	private Short eventSourceId;

	@Column(name = "quantity", nullable = false)
	private BigDecimal quantity;

	@Column(name = "amount")
	private BigDecimal amount;

	@Column(name = "occurred_at", nullable = false)
	private LocalDateTime occurredAt;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	private ConsumptionEvent(Long inventoryItemId, Long userId, String eventType, Short eventSourceId,
			BigDecimal quantity, BigDecimal amount, LocalDateTime occurredAt) {
		this.inventoryItemId = inventoryItemId;
		this.userId = userId;
		this.eventType = eventType;
		this.eventSourceId = eventSourceId;
		this.quantity = quantity;
		this.amount = amount;
		this.occurredAt = occurredAt;
	}

	@PrePersist
	protected void onCreate() {
		this.createdAt = LocalDateTime.now();
	}

	public static ConsumptionEvent create(Long inventoryItemId, Long userId, String eventType,
			Short eventSourceId, BigDecimal quantity, BigDecimal amount) {
		return new ConsumptionEvent(inventoryItemId, userId, eventType, eventSourceId, quantity, amount,
				LocalDateTime.now());
	}
}
