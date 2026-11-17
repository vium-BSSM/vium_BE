package com.vium.post.service;

import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.post.dto.PostDetailResponse;
import com.vium.post.entity.Post;
import com.vium.post.repository.PostRepository;
import com.vium.safety.service.UserBlockService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상세 조회(이슈 2번). EXPIRED·COMPLETED도 조회 가능하므로 PostStateService(OPEN만 잠그는
 * findByIdForUpdate)를 쓰지 않고 PostRepository.findByIdAndDeletedAtIsNull을 직접 쓴다.
 */
@Service
@RequiredArgsConstructor
public class PostDetailService {

	private final PostRepository postRepository;
	private final UserBlockService userBlockService;
	private final PostDetailAssembler postDetailAssembler;

	@Transactional(readOnly = true)
	public PostDetailResponse getDetail(Long userId, Long postId, Double latitude, Double longitude) {
		Post post = postRepository.findByIdAndDeletedAtIsNull(postId)
			.orElseThrow(() -> new BusinessException(ErrorCode.POST_NOT_FOUND));
		if (userBlockService.isBlockedBetween(userId, post.getAuthorId())) {
			throw new BusinessException(ErrorCode.POST_NOT_FOUND);
		}
		return postDetailAssembler.assemble(post, userId, latitude, longitude);
	}
}
