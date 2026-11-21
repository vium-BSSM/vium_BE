package com.vium.notification.entity;

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
@Table(name = "notifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "type", nullable = false, length = 30)
	private String type;

	@Column(name = "inventory_item_id")
	private Long inventoryItemId;

	@Column(name = "title", nullable = false, length = 120)
	private String title;

	@Column(name = "body", length = 500)
	private String body;

	@Column(name = "scheduled_at", nullable = false)
	private LocalDateTime scheduledAt;

	@Column(name = "sent_at")
	private LocalDateTime sentAt;

	@Column(name = "read_at")
	private LocalDateTime readAt;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	public void markRead(LocalDateTime readAt) {
		if (this.readAt == null) {
			this.readAt = readAt;
		}
	}

	public static Notification expiry(Long userId, Long inventoryItemId, String title, String body,
			LocalDateTime scheduledAt, LocalDateTime createdAt) {
		Notification notification = new Notification();
		notification.userId = userId;
		notification.type = "expiry";
		notification.inventoryItemId = inventoryItemId;
		notification.title = title;
		notification.body = body;
		notification.scheduledAt = scheduledAt;
		notification.createdAt = createdAt;
		return notification;
	}

	public void markSent(LocalDateTime sentAt) {
		this.sentAt = sentAt;
	}
}
