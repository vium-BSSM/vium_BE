package com.vium.post.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "post_likes")
@IdClass(PostLikeId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PostLike {

	@Id
	@Column(name = "user_id")
	private Long userId;

	@Id
	@Column(name = "post_id")
	private Long postId;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	public static PostLike create(Long userId, Long postId, LocalDateTime createdAt) {
		PostLike like = new PostLike();
		like.userId = userId;
		like.postId = postId;
		like.createdAt = createdAt;
		return like;
	}
}
