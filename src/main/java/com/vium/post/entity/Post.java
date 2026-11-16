package com.vium.post.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "posts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Post {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "author_id", nullable = false)
	private Long authorId;

	@Column(name = "inventory_item_id", nullable = false)
	private Long inventoryItemId;

	@Enumerated(EnumType.STRING)
	@Column(name = "type", nullable = false, length = 10)
	private PostType type;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private PostStatus status;

	@Column(nullable = false, length = 50)
	private String title;

	@Column(nullable = false, length = 1000)
	private String description;

	@Column(nullable = false)
	private Integer price;

	@Column(name = "recommended_price")
	private Integer recommendedPrice;

	@Column(name = "is_opened", nullable = false)
	private Boolean isOpened;

	@Column(nullable = false)
	private Double latitude;

	@Column(nullable = false)
	private Double longitude;

	@Column(name = "like_count", nullable = false)
	private Integer likeCount;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	@Column(name = "deleted_at")
	private LocalDateTime deletedAt;

	@Builder
	public Post(Long authorId, Long inventoryItemId, PostType type, PostStatus status, String title,
			String description, Integer price, Integer recommendedPrice, Boolean isOpened, Double latitude,
			Double longitude, LocalDateTime createdAt, LocalDateTime updatedAt) {
		this.authorId = authorId;
		this.inventoryItemId = inventoryItemId;
		this.type = type;
		this.status = status;
		this.title = title;
		this.description = description == null ? "" : description;
		this.price = price == null ? 0 : price;
		this.recommendedPrice = recommendedPrice;
		this.isOpened = isOpened;
		this.latitude = latitude;
		this.longitude = longitude;
		this.likeCount = 0;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	public void markReserved(LocalDateTime now) {
		transitionTo(PostStatus.OPEN, PostStatus.RESERVED, now);
	}

	public void markOpen(LocalDateTime now) {
		transitionTo(PostStatus.RESERVED, PostStatus.OPEN, now);
	}

	public void markCompleted(LocalDateTime now) {
		transitionTo(PostStatus.RESERVED, PostStatus.COMPLETED, now);
	}

	public void markExpired(LocalDateTime now) {
		transitionTo(PostStatus.OPEN, PostStatus.EXPIRED, now);
	}

	private void transitionTo(PostStatus requiredCurrent, PostStatus next, LocalDateTime now) {
		if (this.status != requiredCurrent) {
			throw new IllegalStateException(
				"게시물 상태를 " + requiredCurrent + "에서 " + next + "(으)로 바꿀 수 없습니다. 현재 상태: " + this.status);
		}
		this.status = next;
		this.updatedAt = now;
	}
}
