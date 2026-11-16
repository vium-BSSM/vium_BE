package com.vium.post.entity;

import java.io.Serializable;
import java.util.Objects;

public class PostLikeId implements Serializable {

	private Long userId;
	private Long postId;

	public PostLikeId() {
	}

	public PostLikeId(Long userId, Long postId) {
		this.userId = userId;
		this.postId = postId;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof PostLikeId that)) {
			return false;
		}
		return Objects.equals(userId, that.userId) && Objects.equals(postId, that.postId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(userId, postId);
	}
}
