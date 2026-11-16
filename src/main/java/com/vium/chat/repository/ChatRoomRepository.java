package com.vium.chat.repository;

import com.vium.chat.entity.ChatRoom;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

	Optional<ChatRoom> findByPostIdAndBuyerId(Long postId, Long buyerId);
}
