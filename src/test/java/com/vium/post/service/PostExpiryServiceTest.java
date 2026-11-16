package com.vium.post.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
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
	private final PostStateService postStateService = mock(PostStateService.class);
	private final Clock clock = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC);

	private final PostExpiryService service =
		new PostExpiryService(postExpiryQueryRepository, postStateService, clock);

	@Test
	void continuesProcessingRemainingPostsWhenOneThrows() {
		when(postExpiryQueryRepository.findOpenPostIdsWithExpiredIngredient(LocalDate.now(clock)))
			.thenReturn(List.of(1L, 2L, 3L));
		doNothing().when(postStateService).markExpired(1L);
		doThrow(new IllegalStateException("이미 RESERVED")).when(postStateService).markExpired(2L);
		doNothing().when(postStateService).markExpired(3L);

		int processedCount = service.expireOpenPosts();

		assertThat(processedCount).isEqualTo(2);
	}

	@Test
	void doesNotPropagateExceptionWhenAllPostsFail() {
		when(postExpiryQueryRepository.findOpenPostIdsWithExpiredIngredient(LocalDate.now(clock)))
			.thenReturn(List.of(1L, 2L));
		doThrow(new IllegalStateException("boom")).when(postStateService).markExpired(anyLong());

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
		verify(postStateService, never()).markExpired(anyLong());
	}
}
