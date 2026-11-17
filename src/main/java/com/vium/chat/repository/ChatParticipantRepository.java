package com.vium.chat.repository;

import com.vium.chat.entity.ChatParticipant;
import com.vium.chat.entity.ChatParticipantId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatParticipantRepository extends JpaRepository<ChatParticipant, ChatParticipantId> {
}
