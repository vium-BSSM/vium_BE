package com.vium.post.service;

import com.vium.post.entity.Post;
import com.vium.post.entity.PostStatus;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PostExpiryProcessor {
	private final PostStateService postStateService;
	private final List<PostExpiryHandler> handlers;

	@Transactional
	public boolean expire(Long postId) {
		Post post = postStateService.lockForUpdate(postId);
		if (post.getStatus() != PostStatus.OPEN) return false;
		handlers.forEach(handler -> handler.beforeExpire(postId));
		postStateService.markExpired(postId);
		return true;
	}
}
