package com.vium.post.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vium.post.repository.PostExpiryQueryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 항목별 try/catch 복원력만 유닛 테스트로 확인한다. 실제 OPEN/RESERVED 구분과 만료 판정은
 * PostExpiryServiceIntegrationTest에서 실제 DB로 확인한다.
 */
class PostExpiryServiceTest {

	private final PostExpiryQueryRepository postExpiryQueryRepository = mock(PostExpiryQueryRepository.class);
	private final PostExpiryProcessor postExpiryProcessor = mock(PostExpiryProcessor.class);
	private final Clock clock = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC);

	private final PostExpiryService service =
		new PostExpiryService(postExpiryQueryRepository, postExpiryProcessor, clock);

	@Test
	void continuesProcessingRemainingPostsWhenOneThrows() {
		when(postExpiryQueryRepository.findOpenPostIdsWithExpiredIngredient(LocalDate.now(clock)))
			.thenReturn(List.of(1L, 2L, 3L));
		when(postExpiryProcessor.expire(1L)).thenReturn(true);
		doThrow(new IllegalStateException("이미 RESERVED")).when(postExpiryProcessor).expire(2L);
		when(postExpiryProcessor.expire(3L)).thenReturn(true);

		int processedCount = service.expireOpenPosts();

		assertThat(processedCount).isEqualTo(2);
	}

	@Test
	void doesNotPropagateExceptionWhenAllPostsFail() {
		when(postExpiryQueryRepository.findOpenPostIdsWithExpiredIngredient(LocalDate.now(clock)))
			.thenReturn(List.of(1L, 2L));
		doThrow(new IllegalStateException("boom")).when(postExpiryProcessor).expire(anyLong());

		int[] processedCount = new int[1];
		assertThatCode(() -> processedCount[0] = service.expireOpenPosts()).doesNotThrowAnyException();

		assertThat(processedCount[0]).isZero();
	}

	@Test
	void skipsWhenNoCandidates() {
		when(postExpiryQueryRepository.findOpenPostIdsWithExpiredIngredient(LocalDate.now(clock)))
			.thenReturn(List.of());

		int processedCount = service.expireOpenPosts();

		assertThat(processedCount).isZero();
		verify(postExpiryProcessor, never()).expire(anyLong());
	}
}
