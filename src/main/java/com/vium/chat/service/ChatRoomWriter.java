package com.vium.chat.service;

import com.vium.chat.entity.ChatParticipant;
import com.vium.chat.entity.ChatParticipantId;
import com.vium.chat.entity.ChatRoom;
import com.vium.chat.repository.ChatParticipantRepository;
import com.vium.chat.repository.ChatRoomRepository;
import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 방 생성·재참여의 실제 쓰기를 한 트랜잭션으로 묶는다. (post_id, buyer_id) 유니크 위반은 이 메서드를 호출한
 * 트랜잭션 밖(ChatRoomService)에서 잡아 기존 방을 반환해야 한다. 이 클래스 안에서는 예외를 삼키지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ChatRoomWriter {

	private final ChatRoomRepository chatRoomRepository;
	private final ChatParticipantRepository chatParticipantRepository;

	@Transactional
	public ChatRoom createRoom(Long postId, Long buyerId, Long sellerId, LocalDateTime now) {
		ChatRoom room = chatRoomRepository.saveAndFlush(ChatRoom.create(postId, buyerId, sellerId, now));
		chatParticipantRepository.save(ChatParticipant.create(room.getId(), buyerId));
		chatParticipantRepository.save(ChatParticipant.create(room.getId(), sellerId));
		return room;
	}

	@Transactional
	public ChatRoom rejoin(Long postId, Long buyerId) {
		ChatRoom room = chatRoomRepository.findByPostIdAndBuyerId(postId, buyerId)
			.orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
		chatParticipantRepository.findById(new ChatParticipantId(room.getId(), buyerId))
			.ifPresent(ChatParticipant::rejoin);
		return room;
	}
}
