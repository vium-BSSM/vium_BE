package com.vium.auth.service;

import com.vium.auth.repository.UserSessionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 만료 후 보존 기간이 지난 세션을 삭제한다.
 * 스케줄러 활성화 여부와 무관하게 사용할 수 있도록 실행 일정과 분리했다.
 * 서비스에 트랜잭션을 두지 않아 리포지토리 호출마다 한 청크를 커밋한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionCleanupService {
	private final UserSessionRepository userSessionRepository;
	private final Clock authClock;

	public int deleteExpiredSessions(Duration retention, int batchSize) {
		if (retention == null || retention.isNegative() || retention.isZero() || batchSize < 1) {
			throw new IllegalArgumentException("retention and batchSize must be positive");
		}
		LocalDateTime cutoff = LocalDateTime.ofInstant(authClock.instant().minus(retention), ZoneOffset.UTC);
		int deleted = 0;
		try {
			int batch;
			do {
				batch = userSessionRepository.deleteExpiredBefore(cutoff, batchSize);
				deleted += batch;
			} while (batch == batchSize);
		} catch (RuntimeException e) {
			log.error("세션 정리 배치 실패: 삭제 {}건 후 중단", deleted, e);
			throw e;
		}
		log.info("세션 정리 배치 완료: 삭제 {}건, 기준 expires_at < {} (UTC)", deleted, cutoff);
		return deleted;
	}
}
