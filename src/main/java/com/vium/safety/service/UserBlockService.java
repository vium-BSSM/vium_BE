package com.vium.safety.service;

import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.safety.dto.BlockedUserItem;
import com.vium.safety.dto.BlockedUsersResponse;
import com.vium.safety.entity.UserBlock;
import com.vium.safety.repository.SafetyQueryRepository;
import com.vium.safety.repository.UserBlockRepository;
import com.vium.user.service.UserProfileService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserBlockService {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private final UserBlockRepository userBlockRepository;
	private final SafetyQueryRepository safetyQueryRepository;
	private final UserProfileService userProfileService;
	private final Clock clock;

	@Transactional(readOnly = true)
	public boolean isBlockedBetween(Long userA, Long userB) {
		return userBlockRepository.existsBetween(userA, userB);
	}

	@Transactional(readOnly = true)
	public Set<Long> findRelatedUserIds(Long userId) {
		Set<Long> relatedUserIds = new HashSet<>(userBlockRepository.findBlockedIdsByBlockerId(userId));
		relatedUserIds.addAll(userBlockRepository.findBlockerIdsByBlockedId(userId));
		return relatedUserIds;
	}

	@Transactional(readOnly = true)
	public BlockedUsersResponse listBlockedUsers(Long blockerId) {
		return new BlockedUsersResponse(safetyQueryRepository.findBlockedUsers(blockerId).stream()
			.map(row -> new BlockedUserItem(row.userId(), row.displayName(), row.blockedAt().atZone(SEOUL).toOffsetDateTime()))
			.toList());
	}

	@Transactional
	public void block(Long blockerId, Long blockedId) {
		if (blockerId.equals(blockedId)) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, "자기 자신을 차단할 수 없습니다");
		}
		userProfileService.requireActiveUser(blockedId);
		if (userBlockRepository.existsByBlockerIdAndBlockedId(blockerId, blockedId)) {
			return;
		}
		try {
			userBlockRepository.saveAndFlush(UserBlock.create(blockerId, blockedId, LocalDateTime.now(clock)));
		} catch (DataIntegrityViolationException alreadyBlockedByConcurrentRequest) {
			// 멱등 처리: 동시 요청이 이미 같은 차단 행을 만들었다.
		}
	}

	@Transactional
	public void unblock(Long blockerId, Long blockedId) {
		userProfileService.requireActiveUser(blockedId);
		userBlockRepository.deleteByBlockerIdAndBlockedId(blockerId, blockedId);
	}
}
