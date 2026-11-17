package com.vium.chat.service;

import com.vium.chat.dto.ChatRoomListResponse;
import com.vium.chat.dto.ChatRoomListRow;
import com.vium.chat.dto.CreateChatRoomResponse;
import com.vium.chat.entity.ChatParticipant;
import com.vium.chat.entity.ChatParticipantId;
import com.vium.chat.entity.ChatRoom;
import com.vium.chat.repository.ChatParticipantRepository;
import com.vium.chat.repository.ChatRoomQueryRepository;
import com.vium.chat.repository.ChatRoomRepository;
import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.post.dto.PostBasicInfo;
import com.vium.post.service.PostReader;
import com.vium.safety.service.UserBlockService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatRoomService {

	private static final int DEFAULT_SIZE = 20;
	private static final int MAX_SIZE = 50;
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private final ChatRoomRepository chatRoomRepository;
	private final ChatParticipantRepository chatParticipantRepository;
	private final ChatRoomQueryRepository chatRoomQueryRepository;
	private final ChatRoomWriter chatRoomWriter;
	private final PostReader postReader;
	private final UserBlockService userBlockService;
	private final Clock clock;

	@Transactional(readOnly = true)
	public ChatRoomListResponse list(Long userId, String cursor, Integer size) {
		int effectiveSize = size == null ? DEFAULT_SIZE : size;
		if (effectiveSize < 1 || effectiveSize > MAX_SIZE) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, "size는 1~" + MAX_SIZE + " 사이여야 합니다");
		}

		LocalDateTime cursorActivityAt = null;
		Long cursorRoomId = null;
		if (cursor != null && !cursor.isBlank()) {
			String[] parts = cursor.split("_", 2);
			try {
				long epochMilli = Long.parseLong(parts[0]);
				cursorRoomId = Long.parseLong(parts[1]);
				cursorActivityAt = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), SEOUL);
			} catch (RuntimeException e) {
				throw new BusinessException(ErrorCode.INVALID_REQUEST, "cursor 값이 올바르지 않습니다");
			}
		}

		List<ChatRoomListRow> rows =
			chatRoomQueryRepository.findRooms(userId, cursorActivityAt, cursorRoomId, effectiveSize + 1);
		boolean hasNext = rows.size() > effectiveSize;
		List<ChatRoomListRow> page = hasNext ? rows.subList(0, effectiveSize) : rows;

		String nextCursor = null;
		if (hasNext) {
			ChatRoomListRow last = page.get(page.size() - 1);
			nextCursor = encodeCursor(last.activityAt(), last.summary().chatRoomId());
		}

		return new ChatRoomListResponse(page.stream().map(ChatRoomListRow::summary).toList(), nextCursor, hasNext);
	}

	@Transactional
	public void leave(Long userId, Long chatRoomId) {
		ChatParticipant participant = chatParticipantRepository.findById(new ChatParticipantId(chatRoomId, userId))
			.orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
		participant.leave(LocalDateTime.now(clock));
	}

	public CreateChatRoomResponse create(Long userId, Long postId) {
		PostBasicInfo post = postReader.findBasicInfo(postId)
			.orElseThrow(() -> new BusinessException(ErrorCode.POST_NOT_FOUND));
		if (post.authorId().equals(userId)) {
			throw new BusinessException(ErrorCode.CANNOT_CHAT_OWN_POST);
		}
		if (userBlockService.isBlockedBetween(userId, post.authorId())) {
			throw new BusinessException(ErrorCode.USER_BLOCKED);
		}

		Optional<ChatRoom> existing = chatRoomRepository.findByPostIdAndBuyerId(postId, userId);
		if (existing.isPresent()) {
			ChatRoom room = chatRoomWriter.rejoin(postId, userId);
			return new CreateChatRoomResponse(room.getId(), false);
		}

		LocalDateTime now = LocalDateTime.now(clock);
		try {
			ChatRoom created = chatRoomWriter.createRoom(postId, userId, post.authorId(), now);
			return new CreateChatRoomResponse(created.getId(), true);
		} catch (DataIntegrityViolationException concurrentCreate) {
			ChatRoom room = chatRoomWriter.rejoin(postId, userId);
			return new CreateChatRoomResponse(room.getId(), false);
		}
	}

	private String encodeCursor(LocalDateTime activityAt, Long chatRoomId) {
		long epochMilli = activityAt.atZone(SEOUL).toInstant().toEpochMilli();
		return epochMilli + "_" + chatRoomId;
	}
}
