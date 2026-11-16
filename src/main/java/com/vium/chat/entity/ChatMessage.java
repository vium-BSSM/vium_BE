package com.vium.chat.entity;

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
@Table(name = "chat_messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessage {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "chat_room_id", nullable = false)
	private Long chatRoomId;

	@Column(name = "sender_id")
	private Long senderId;

	@Enumerated(EnumType.STRING)
	@Column(name = "type", nullable = false, length = 10)
	private ChatMessageType type;

	@Column(name = "content", length = 1000)
	private String content;

	@Column(name = "image_id")
	private Long imageId;

	@Column(name = "system_event", length = 30)
	private String systemEvent;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	public static ChatMessage text(Long chatRoomId, Long senderId, String content, LocalDateTime now) {
		ChatMessage message = new ChatMessage();
		message.chatRoomId = chatRoomId;
		message.senderId = senderId;
		message.type = ChatMessageType.TEXT;
		message.content = content;
		message.createdAt = now;
		return message;
	}

	public static ChatMessage image(Long chatRoomId, Long senderId, Long imageId, LocalDateTime now) {
		ChatMessage message = new ChatMessage();
		message.chatRoomId = chatRoomId;
		message.senderId = senderId;
		message.type = ChatMessageType.IMAGE;
		message.imageId = imageId;
		message.createdAt = now;
		return message;
	}

	public static ChatMessage system(Long chatRoomId, String content, String systemEvent, LocalDateTime now) {
		ChatMessage message = new ChatMessage();
		message.chatRoomId = chatRoomId;
		message.senderId = null;
		message.type = ChatMessageType.SYSTEM;
		message.content = content;
		message.systemEvent = systemEvent;
		message.createdAt = now;
		return message;
	}
}
