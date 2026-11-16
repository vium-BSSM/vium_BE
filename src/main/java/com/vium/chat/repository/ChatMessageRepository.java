package com.vium.chat.repository;

import com.vium.chat.entity.ChatMessage;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

	List<ChatMessage> findByChatRoomIdOrderByIdDesc(Long chatRoomId, Pageable pageable);

	List<ChatMessage> findByChatRoomIdAndIdLessThanOrderByIdDesc(Long chatRoomId, Long id, Pageable pageable);

	Optional<ChatMessage> findTopByChatRoomIdOrderByIdDesc(Long chatRoomId);
}
