package com.vium.post.repository;

import com.vium.post.entity.PostLike;
import com.vium.post.entity.PostLikeId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostLikeRepository extends JpaRepository<PostLike, PostLikeId> {

	boolean existsByUserIdAndPostId(Long userId, Long postId);

	void deleteByUserIdAndPostId(Long userId, Long postId);
}
