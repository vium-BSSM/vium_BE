package com.vium.post.repository;

import com.vium.post.entity.PostImage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostImageRepository extends JpaRepository<PostImage, Long> {

	List<PostImage> findByPostIdOrderBySortOrderAsc(Long postId);

	void deleteByPostId(Long postId);
}
