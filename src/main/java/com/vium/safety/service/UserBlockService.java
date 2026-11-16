package com.vium.safety.service;

import com.vium.safety.repository.UserBlockRepository;
import java.util.HashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserBlockService {

	private final UserBlockRepository userBlockRepository;

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
}
