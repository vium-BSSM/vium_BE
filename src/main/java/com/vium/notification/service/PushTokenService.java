package com.vium.notification.service;

import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.notification.entity.PushPlatform;
import com.vium.notification.entity.UserPushToken;
import com.vium.notification.repository.UserPushTokenRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * register()는 의도적으로 @Transactional을 붙이지 않는다. 같은 새 토큰이 동시에 등록되면
 * 먼저 커밋된 트랜잭션이 행을 만들고, 나중 트랜잭션은 UNIQUE 위반으로 실패한다.
 * PostgreSQL은 제약 위반이 나면 그 트랜잭션 전체를 중단 상태로 만들어 같은 트랜잭션 안에서는
 * 재조회조차 할 수 없으므로, 실패한 시도를 완전히 롤백시킨 뒤 별도의 새 트랜잭션에서 재조회해
 * 소유자를 이전해야 한다. self(프록시)를 통해 호출해야 각 시도가 독립된 트랜잭션으로 실행된다.
 */
@Service
@RequiredArgsConstructor
public class PushTokenService {

	private final UserPushTokenRepository userPushTokenRepository;
	private final Clock clock;
	private final ObjectProvider<PushTokenService> self;

	public Long register(Long userId, String token, PushPlatform platform) {
		try {
			return self.getObject().insertOrReassign(userId, token, platform);
		} catch (DataIntegrityViolationException e) {
			return self.getObject().reassignAfterConflict(userId, token, platform);
		}
	}

	@Transactional
	public Long insertOrReassign(Long userId, String token, PushPlatform platform) {
		LocalDateTime now = LocalDateTime.now(clock);
		return userPushTokenRepository.findByToken(token)
			.map(existing -> {
				existing.reassign(userId, platform, now);
				return existing.getId();
			})
			.orElseGet(() -> userPushTokenRepository
				.saveAndFlush(UserPushToken.register(userId, token, platform, now))
				.getId());
	}

	@Transactional
	public Long reassignAfterConflict(Long userId, String token, PushPlatform platform) {
		LocalDateTime now = LocalDateTime.now(clock);
		UserPushToken existing = userPushTokenRepository.findByToken(token)
			.orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "토큰 등록 충돌 후 재조회에 실패했습니다"));
		existing.reassign(userId, platform, now);
		return existing.getId();
	}

	@Transactional
	public void unregister(Long userId, String token) {
		userPushTokenRepository.deleteByUserIdAndToken(userId, token);
	}

	@Transactional
	public void deleteAllByUserId(Long userId) {
		userPushTokenRepository.deleteAllByUserId(userId);
	}

	@Transactional
	public void deleteByToken(String token) {
		userPushTokenRepository.deleteByToken(token);
	}
}
