package com.vium.chat.entity;

import java.io.Serializable;
import java.util.Objects;

public class ChatParticipantId implements Serializable {

	private Long chatRoomId;
	private Long userId;

	public ChatParticipantId() {
	}

	public ChatParticipantId(Long chatRoomId, Long userId) {
		this.chatRoomId = chatRoomId;
		this.userId = userId;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof ChatParticipantId that)) {
			return false;
		}
		return Objects.equals(chatRoomId, that.chatRoomId) && Objects.equals(userId, that.userId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(chatRoomId, userId);
	}
}
