package com.vium.post.service;

import com.vium.post.repository.PostExpiryQueryRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 소비기한이 지난 OPEN 게시물을 EXPIRED로 바꾼다. 스케줄러({@link PostExpiryScheduler})와
 * post.expiry.enabled 값과 무관하게 항상 빈으로 존재해야 하므로, 활성화 조건은 스케줄러에 둔다.
 * AutoDisposeService와 같은 원칙: 게시물 한 건 실패가 나머지를 막지 않는다(항목별 try/catch,
 * PostExpiryProcessor.expire가 각자 자기 트랜잭션에서 실행된다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostExpiryService {

	private final PostExpiryQueryRepository postExpiryQueryRepository;
	private final PostExpiryProcessor postExpiryProcessor;
	private final Clock clock;

	public int expireOpenPosts() {
		LocalDate today = LocalDate.now(clock);
		List<Long> postIds = postExpiryQueryRepository.findOpenPostIdsWithExpiredIngredient(today);

		int processedCount = 0;
		int failedCount = 0;
		for (Long postId : postIds) {
			try {
				if (postExpiryProcessor.expire(postId)) processedCount++;
			} catch (Exception e) {
				failedCount++;
				log.error("게시물 만료 처리 실패: postId={}", postId, e);
			}
		}

		String summary = String.format("게시물 만료 배치 완료: 처리 %d건, 실패 %d건", processedCount, failedCount);
		if (failedCount > 0) {
			log.warn(summary);
		} else {
			log.info(summary);
		}

		return processedCount;
	}
}
