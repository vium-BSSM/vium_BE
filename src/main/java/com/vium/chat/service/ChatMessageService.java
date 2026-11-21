package com.vium.chat.service;

import com.vium.chat.dto.ChatMessageDto;
import com.vium.chat.dto.ChatMessageListResponse;
import com.vium.chat.dto.SendChatMessageRequest;
import com.vium.chat.entity.ChatMessage;
import com.vium.chat.entity.ChatMessageType;
import com.vium.chat.entity.ChatParticipant;
import com.vium.chat.entity.ChatParticipantId;
import com.vium.chat.entity.ChatRoom;
import com.vium.chat.repository.ChatMessageRepository;
import com.vium.chat.repository.ChatParticipantRepository;
import com.vium.chat.repository.ChatRoomRepository;
import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.image.service.ImageService;
import com.vium.safety.service.UserBlockService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatMessageService {

	private static final int DEFAULT_SIZE = 30;
	private static final int MAX_SIZE = 100;
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private final ChatRoomRepository chatRoomRepository;
	private final ChatParticipantRepository chatParticipantRepository;
	private final ChatMessageRepository chatMessageRepository;
	private final UserBlockService userBlockService;
	private final ImageService imageService;
	private final Clock clock;
	private final ApplicationEventPublisher eventPublisher;

	@Transactional
	public ChatMessageListResponse getHistory(Long userId, Long chatRoomId, String cursor, Integer size) {
		ChatParticipant participant = chatParticipantRepository.findById(new ChatParticipantId(chatRoomId, userId))
			.orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));

		int effectiveSize = size == null ? DEFAULT_SIZE : size;
		if (effectiveSize < 1 || effectiveSize > MAX_SIZE) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, "size는 1~" + MAX_SIZE + " 사이여야 합니다");
		}
		Long cursorId = parseCursor(cursor);

		var rows = cursorId == null
			? chatMessageRepository.findByChatRoomIdOrderByIdDesc(chatRoomId, PageRequest.of(0, effectiveSize + 1))
			: chatMessageRepository.findByChatRoomIdAndIdLessThanOrderByIdDesc(chatRoomId, cursorId,
				PageRequest.of(0, effectiveSize + 1));

		boolean hasNext = rows.size() > effectiveSize;
		var page = hasNext ? rows.subList(0, effectiveSize) : rows;
		String nextCursor = hasNext ? String.valueOf(page.get(page.size() - 1).getId()) : null;

		chatMessageRepository.findTopByChatRoomIdOrderByIdDesc(chatRoomId)
			.ifPresent(latest -> participant.markRead(latest.getId()));

		return new ChatMessageListResponse(page.stream().map(m -> toDto(m, userId)).toList(), nextCursor, hasNext);
	}

	@Transactional
	public ChatMessageDto send(Long userId, Long chatRoomId, SendChatMessageRequest request) {
		ChatRoom room = chatRoomRepository.findById(chatRoomId)
			.orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
		chatParticipantRepository.findById(new ChatParticipantId(chatRoomId, userId))
			.orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));

		validateRequest(userId, request);

		Long partnerId = room.partnerIdFor(userId);
		if (userBlockService.isBlockedBetween(userId, partnerId)) {
			throw new BusinessException(ErrorCode.USER_BLOCKED);
		}

		LocalDateTime now = LocalDateTime.now(clock);
		ChatMessage message = request.type() == ChatMessageType.TEXT
			? ChatMessage.text(chatRoomId, userId, request.content(), now)
			: ChatMessage.image(chatRoomId, userId, request.imageId(), now);
		message = chatMessageRepository.save(message);

		room.touchLastMessage(now);
		chatParticipantRepository.findById(new ChatParticipantId(chatRoomId, partnerId))
			.ifPresent(ChatParticipant::rejoin);

		eventPublisher.publishEvent(new ChatMessageSentEvent(chatRoomId, partnerId));
		return toDto(message, userId);
	}

	private void validateRequest(Long userId, SendChatMessageRequest request) {
		if (request == null || request.type() == null) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		switch (request.type()) {
			case TEXT -> {
				if (request.content() == null || request.content().isEmpty() || request.content().length() > 1000) {
					throw new BusinessException(ErrorCode.INVALID_REQUEST);
				}
			}
			case IMAGE -> {
				if (request.imageId() == null) {
					throw new BusinessException(ErrorCode.INVALID_REQUEST);
				}
				imageService.requireChatImage(userId, request.imageId());
			}
			default -> throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
	}

	private Long parseCursor(String cursor) {
		if (cursor == null || cursor.isBlank()) {
			return null;
		}
		try {
			return Long.parseLong(cursor);
		} catch (NumberFormatException e) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, "cursor 값이 올바르지 않습니다");
		}
	}

	private ChatMessageDto toDto(ChatMessage message, Long currentUserId) {
		String imageUrl = message.getType() == ChatMessageType.IMAGE
			? imageService.accessChatImage(message.getImageId()).url()
			: null;
		boolean isMine = message.getSenderId() != null && message.getSenderId().equals(currentUserId);
		return new ChatMessageDto(message.getId(), message.getChatRoomId(), message.getSenderId(), message.getType(),
			message.getContent(), imageUrl, message.getSystemEvent(),
			message.getCreatedAt().atZone(SEOUL).toOffsetDateTime(), isMine);
	}
}
