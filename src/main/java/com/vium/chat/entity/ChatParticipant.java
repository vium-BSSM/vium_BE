package com.vium.chat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "chat_participants")
@IdClass(ChatParticipantId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatParticipant {

	@Id
	@Column(name = "chat_room_id")
	private Long chatRoomId;

	@Id
	@Column(name = "user_id")
	private Long userId;

	@Column(name = "last_read_message_id")
	private Long lastReadMessageId;

	@Column(name = "left_at")
	private LocalDateTime leftAt;

	public static ChatParticipant create(Long chatRoomId, Long userId) {
		ChatParticipant participant = new ChatParticipant();
		participant.chatRoomId = chatRoomId;
		participant.userId = userId;
		return participant;
	}

	public void leave(LocalDateTime now) {
		this.leftAt = now;
	}

	public void rejoin() {
		this.leftAt = null;
	}

	public void markRead(Long messageId) {
		if (messageId == null) {
			return;
		}
		if (this.lastReadMessageId == null || messageId > this.lastReadMessageId) {
			this.lastReadMessageId = messageId;
		}
	}
}
