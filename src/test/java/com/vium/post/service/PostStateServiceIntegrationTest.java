package com.vium.post.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.post.entity.Post;
import com.vium.post.entity.PostStatus;
import com.vium.post.entity.PostType;
import com.vium.post.repository.PostRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:post-state-service-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@Transactional
class PostStateServiceIntegrationTest {

	@Autowired
	private PostStateService postStateService;

	@Autowired
	private PostRepository postRepository;

	@Autowired
	private Clock clock;

	private Post openPost;
	private Post reservedPost;

	@BeforeEach
	void setUp() {
		LocalDateTime now = LocalDateTime.now(clock);
		openPost = postRepository.save(newPost(PostStatus.OPEN, now));
		reservedPost = postRepository.save(newPost(PostStatus.RESERVED, now));
	}

	private Post newPost(PostStatus status, LocalDateTime now) {
		return Post.builder()
			.authorId(1L)
			.inventoryItemId(10L)
			.type(PostType.SHARE)
			.status(status)
			.title("제목")
			.description("설명")
			.price(0)
			.isOpened(false)
			.latitude(37.5)
			.longitude(127.0)
			.createdAt(now)
			.updatedAt(now)
			.build();
	}

	@Test
	void markReservedTransitionsOpenToReserved() {
		postStateService.markReserved(openPost.getId());

		Post reloaded = postRepository.findById(openPost.getId()).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(PostStatus.RESERVED);
	}

	@Test
	void markReservedThrowsWhenPostIsNotOpen() {
		assertThatThrownBy(() -> postStateService.markReserved(reservedPost.getId()))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void markOpenTransitionsReservedToOpen() {
		postStateService.markOpen(reservedPost.getId());

		Post reloaded = postRepository.findById(reservedPost.getId()).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(PostStatus.OPEN);
	}

	@Test
	void markCompletedTransitionsReservedToCompleted() {
		postStateService.markCompleted(reservedPost.getId());

		Post reloaded = postRepository.findById(reservedPost.getId()).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(PostStatus.COMPLETED);
	}

	@Test
	void markExpiredTransitionsOpenToExpired() {
		postStateService.markExpired(openPost.getId());

		Post reloaded = postRepository.findById(openPost.getId()).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(PostStatus.EXPIRED);
	}

	@Test
	void lockForUpdateThrowsPostNotFoundForMissingPost() {
		assertThatThrownBy(() -> postStateService.lockForUpdate(999_999L))
			.isInstanceOf(BusinessException.class)
			.satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.POST_NOT_FOUND));
	}

	@Test
	void lockForUpdateThrowsPostNotFoundForDeletedPost() {
		Post deleted = postRepository.save(newPost(PostStatus.OPEN, LocalDateTime.now(clock)));
		softDelete(deleted);

		assertThatThrownBy(() -> postStateService.lockForUpdate(deleted.getId()))
			.isInstanceOf(BusinessException.class)
			.satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.POST_NOT_FOUND));
	}

	private void softDelete(Post post) {
		org.springframework.test.util.ReflectionTestUtils.setField(post, "deletedAt", LocalDateTime.now(clock));
		postRepository.saveAndFlush(post);
	}
}
