package com.vium.chat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "chat_rooms", uniqueConstraints = @UniqueConstraint(columnNames = {"post_id", "buyer_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatRoom {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "post_id", nullable = false)
	private Long postId;

	@Column(name = "buyer_id", nullable = false)
	private Long buyerId;

	@Column(name = "seller_id", nullable = false)
	private Long sellerId;

	@Column(name = "last_message_at")
	private LocalDateTime lastMessageAt;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	public static ChatRoom create(Long postId, Long buyerId, Long sellerId, LocalDateTime now) {
		ChatRoom room = new ChatRoom();
		room.postId = postId;
		room.buyerId = buyerId;
		room.sellerId = sellerId;
		room.createdAt = now;
		return room;
	}

	public Long partnerIdFor(Long userId) {
		return buyerId.equals(userId) ? sellerId : buyerId;
	}

	public void touchLastMessage(LocalDateTime now) {
		this.lastMessageAt = now;
	}
}
