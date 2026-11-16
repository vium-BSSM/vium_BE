package com.vium.post.service;

import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.post.entity.Post;
import com.vium.post.repository.PostRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 게시물 상태 전이를 담당한다. 다른 도메인(거래 등)이 호출자 트랜잭션 안에서 함께 쓸 수 있도록
 * 모든 메서드는 기본 전파(@Transactional) 레벨을 유지한다.
 */
@Service
@RequiredArgsConstructor
public class PostStateService {

	private final PostRepository postRepository;
	private final Clock clock;

	@Transactional
	public Post lockForUpdate(Long postId) {
		return postRepository.findByIdForUpdate(postId)
			.orElseThrow(() -> new BusinessException(ErrorCode.POST_NOT_FOUND));
	}

	@Transactional
	public void markReserved(Long postId) {
		lockForUpdate(postId).markReserved(LocalDateTime.now(clock));
	}

	@Transactional
	public void markOpen(Long postId) {
		lockForUpdate(postId).markOpen(LocalDateTime.now(clock));
	}

	@Transactional
	public void markCompleted(Long postId) {
		lockForUpdate(postId).markCompleted(LocalDateTime.now(clock));
	}

	@Transactional
	public void markExpired(Long postId) {
		lockForUpdate(postId).markExpired(LocalDateTime.now(clock));
	}
}
