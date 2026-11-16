package com.vium.post.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class PostTest {

	private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 1, 1, 0, 0);

	private Post newPost(PostStatus status) {
		return Post.builder()
			.authorId(1L)
			.inventoryItemId(10L)
			.type(PostType.SHARE)
			.status(status)
			.title("제목")
			.description("설명")
			.price(0)
			.recommendedPrice(null)
			.isOpened(false)
			.latitude(37.5)
			.longitude(127.0)
			.createdAt(BASE_TIME)
			.updatedAt(BASE_TIME)
			.build();
	}

	@Test
	void markReservedSucceedsFromOpen() {
		Post post = newPost(PostStatus.OPEN);
		LocalDateTime now = BASE_TIME.plusMinutes(1);

		post.markReserved(now);

		assertThat(post.getStatus()).isEqualTo(PostStatus.RESERVED);
		assertThat(post.getUpdatedAt()).isEqualTo(now);
	}

	@Test
	void markReservedFailsWhenNotOpen() {
		Post post = newPost(PostStatus.RESERVED);

		assertThatThrownBy(() -> post.markReserved(BASE_TIME))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void markOpenSucceedsFromReserved() {
		Post post = newPost(PostStatus.RESERVED);
		LocalDateTime now = BASE_TIME.plusMinutes(1);

		post.markOpen(now);

		assertThat(post.getStatus()).isEqualTo(PostStatus.OPEN);
		assertThat(post.getUpdatedAt()).isEqualTo(now);
	}

	@Test
	void markOpenFailsWhenNotReserved() {
		Post post = newPost(PostStatus.OPEN);

		assertThatThrownBy(() -> post.markOpen(BASE_TIME))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void markCompletedSucceedsFromReserved() {
		Post post = newPost(PostStatus.RESERVED);
		LocalDateTime now = BASE_TIME.plusMinutes(1);

		post.markCompleted(now);

		assertThat(post.getStatus()).isEqualTo(PostStatus.COMPLETED);
		assertThat(post.getUpdatedAt()).isEqualTo(now);
	}

	@Test
	void markCompletedFailsWhenNotReserved() {
		Post post = newPost(PostStatus.OPEN);

		assertThatThrownBy(() -> post.markCompleted(BASE_TIME))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void markExpiredSucceedsFromOpen() {
		Post post = newPost(PostStatus.OPEN);
		LocalDateTime now = BASE_TIME.plusMinutes(1);

		post.markExpired(now);

		assertThat(post.getStatus()).isEqualTo(PostStatus.EXPIRED);
		assertThat(post.getUpdatedAt()).isEqualTo(now);
	}

	@Test
	void markExpiredFailsWhenNotOpen() {
		Post post = newPost(PostStatus.RESERVED);

		assertThatThrownBy(() -> post.markExpired(BASE_TIME))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void markExpiredFailsFromTerminalStatus() {
		Post post = newPost(PostStatus.COMPLETED);

		assertThatThrownBy(() -> post.markExpired(BASE_TIME))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void descriptionDefaultsToEmptyStringWhenNull() {
		Post post = Post.builder()
			.authorId(1L)
			.inventoryItemId(10L)
			.type(PostType.SHARE)
			.status(PostStatus.OPEN)
			.title("제목")
			.description(null)
			.price(null)
			.isOpened(false)
			.latitude(37.5)
			.longitude(127.0)
			.createdAt(BASE_TIME)
			.updatedAt(BASE_TIME)
			.build();

		assertThat(post.getDescription()).isEmpty();
		assertThat(post.getPrice()).isZero();
		assertThat(post.getLikeCount()).isZero();
	}
}
