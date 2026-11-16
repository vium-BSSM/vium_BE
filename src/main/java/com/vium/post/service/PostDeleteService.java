package com.vium.post.service;

import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.post.entity.Post;
import com.vium.post.entity.PostStatus;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PostDeleteService {

	private final PostStateService postStateService;
	private final Clock clock;

	@Transactional
	public void delete(Long userId, Long postId) {
		Post post = postStateService.lockForUpdate(postId);
		if (!post.isOwnedBy(userId)) {
			throw new BusinessException(ErrorCode.POST_NOT_FOUND);
		}
		if (post.getStatus() == PostStatus.RESERVED || post.getStatus() == PostStatus.COMPLETED) {
			throw new BusinessException(ErrorCode.POST_LOCKED, "거래가 진행 중이거나 완료된 게시물은 삭제할 수 없습니다.");
		}
		post.softDelete(LocalDateTime.now(clock));
	}
}
