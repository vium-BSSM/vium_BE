package com.vium.post.repository;

import com.vium.post.entity.Post;
import com.vium.post.entity.PostStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostRepository extends JpaRepository<Post, Long> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT p FROM Post p WHERE p.id = :id AND p.deletedAt IS NULL")
	Optional<Post> findByIdForUpdate(@Param("id") Long id);

	boolean existsByInventoryItemIdAndDeletedAtIsNullAndStatusIn(Long inventoryItemId, Collection<PostStatus> statuses);
}
