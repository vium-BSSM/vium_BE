package com.vium.post.service;

import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.post.dto.PostLikeResponse;
import com.vium.post.entity.Post;
import com.vium.post.entity.PostLike;
import com.vium.post.repository.PostLikeRepository;
import com.vium.safety.service.UserBlockService;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PostLikeService {

	private final PostStateService postStateService;
	private final PostLikeRepository postLikeRepository;
	private final UserBlockService userBlockService;
	private final Clock clock;

	@Transactional
	public PostLikeResponse like(Long userId, Long postId) {
		Post post = findVisiblePost(userId, postId);
		if (!postLikeRepository.existsByUserIdAndPostId(userId, postId)) {
			try {
				postLikeRepository.saveAndFlush(PostLike.create(userId, postId, LocalDateTime.now(clock)));
				post.incrementLikeCount();
			} catch (DataIntegrityViolationException alreadyLikedByConcurrentRequest) {
				// 멱등 처리: 동시 요청이 이미 같은 좋아요 행을 만들었다.
			}
		}
		return new PostLikeResponse(postId, true, post.getLikeCount());
	}

	@Transactional
	public PostLikeResponse unlike(Long userId, Long postId) {
		Post post = findVisiblePost(userId, postId);
		if (postLikeRepository.existsByUserIdAndPostId(userId, postId)) {
			postLikeRepository.deleteByUserIdAndPostId(userId, postId);
			post.decrementLikeCount();
		}
		return new PostLikeResponse(postId, false, post.getLikeCount());
	}

	private Post findVisiblePost(Long userId, Long postId) {
		Post post = postStateService.lockForUpdate(postId);
		if (userBlockService.isBlockedBetween(userId, post.getAuthorId())) {
			throw new BusinessException(ErrorCode.POST_NOT_FOUND);
		}
		return post;
	}
}
