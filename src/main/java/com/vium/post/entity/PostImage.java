package com.vium.post.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "post_images")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PostImage {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "post_id", nullable = false)
	private Long postId;

	@Column(name = "image_id", nullable = false)
	private Long imageId;

	@Column(name = "sort_order", nullable = false)
	private Short sortOrder;

	@Builder
	public PostImage(Long postId, Long imageId, Short sortOrder) {
		this.postId = postId;
		this.imageId = imageId;
		this.sortOrder = sortOrder;
	}
}
