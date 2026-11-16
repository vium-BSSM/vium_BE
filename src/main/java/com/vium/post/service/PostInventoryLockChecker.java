package com.vium.post.service;

import com.vium.inventory.service.InventoryLockChecker;
import com.vium.post.entity.PostStatus;
import com.vium.post.repository.PostRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 삭제되지 않았고 OPEN 또는 RESERVED 상태인 게시물이 있는 재고를 잠근다.
 * COMPLETED·EXPIRED 게시물은 잠그지 않는다.
 */
@Component
@RequiredArgsConstructor
public class PostInventoryLockChecker implements InventoryLockChecker {

	private static final List<PostStatus> LOCKING_STATUSES = List.of(PostStatus.OPEN, PostStatus.RESERVED);

	private final PostRepository postRepository;

	@Override
	public boolean isLocked(Long inventoryItemId) {
		return postRepository.existsByInventoryItemIdAndDeletedAtIsNullAndStatusIn(inventoryItemId, LOCKING_STATUSES);
	}
}
