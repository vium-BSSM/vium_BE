package com.vium.dispose;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.vium.dispose.service.AutoDisposeItemProcessor;
import com.vium.dispose.service.AutoDisposeService;
import com.vium.global.code.EventSource;
import com.vium.global.code.EventSourceRepository;
import com.vium.global.code.ItemStatus;
import com.vium.global.code.ItemStatusRepository;
import com.vium.inventory.dto.AutoDisposeCandidate;
import com.vium.inventory.service.InventoryService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class AutoDisposeServiceTest {

	private static final Short ACTIVE_STATUS_ID = 1;
	private static final Short DISPOSED_STATUS_ID = 3;
	private static final Short EVENT_SOURCE_ID = 2;

	private final InventoryService inventoryService = mock(InventoryService.class);
	private final EventSourceRepository eventSourceRepository = mock(EventSourceRepository.class);
	private final ItemStatusRepository itemStatusRepository = mock(ItemStatusRepository.class);
	private final AutoDisposeItemProcessor itemProcessor = mock(AutoDisposeItemProcessor.class);
	private final Clock clock = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC);

	private final Logger logger = (Logger) LoggerFactory.getLogger(AutoDisposeService.class);
	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

	private AutoDisposeService service;

	@BeforeEach
	void setUp() {
		appender.start();
		logger.addAppender(appender);

		ItemStatus activeStatus = mock(ItemStatus.class);
		when(activeStatus.getId()).thenReturn(ACTIVE_STATUS_ID);
		ItemStatus disposedStatus = mock(ItemStatus.class);
		when(disposedStatus.getId()).thenReturn(DISPOSED_STATUS_ID);
		when(itemStatusRepository.findByCode("active")).thenReturn(Optional.of(activeStatus));
		when(itemStatusRepository.findByCode("disposed")).thenReturn(Optional.of(disposedStatus));

		EventSource eventSource = mock(EventSource.class);
		when(eventSource.getId()).thenReturn(EVENT_SOURCE_ID);
		when(eventSourceRepository.findByCode("auto_estimated")).thenReturn(Optional.of(eventSource));

		service = new AutoDisposeService(
			inventoryService, eventSourceRepository, itemStatusRepository, itemProcessor, clock);
	}

	@AfterEach
	void tearDown() {
		logger.detachAppender(appender);
		appender.stop();
	}

	private AutoDisposeCandidate candidate(long inventoryItemId) {
		return new AutoDisposeCandidate(inventoryItemId, 42L, BigDecimal.ONE, BigDecimal.TEN,
			BigDecimal.valueOf(1000), LocalDate.now(clock).minusDays(1));
	}

	private void givenCandidates(AutoDisposeCandidate... candidates) {
		when(inventoryService.findAutoDisposeCandidates(ACTIVE_STATUS_ID, LocalDate.now(clock)))
			.thenReturn(List.of(candidates));
	}

	@Test
	void continuesProcessingRemainingItemsWhenOneThrows() {
		AutoDisposeCandidate first = candidate(1L);
		AutoDisposeCandidate failing = candidate(2L);
		AutoDisposeCandidate last = candidate(3L);
		givenCandidates(first, failing, last);
		when(itemProcessor.disposeOneItem(eq(first), any(), any(), any(), any())).thenReturn(true);
		when(itemProcessor.disposeOneItem(eq(failing), any(), any(), any(), any()))
			.thenThrow(new RuntimeException("boom"));
		when(itemProcessor.disposeOneItem(eq(last), any(), any(), any(), any())).thenReturn(true);

		int processedCount = service.disposeExpiredItems();

		assertThat(processedCount).isEqualTo(2);
	}

	@Test
	void doesNotPropagateExceptionWhenAllItemsFail() {
		AutoDisposeCandidate first = candidate(1L);
		AutoDisposeCandidate second = candidate(2L);
		givenCandidates(first, second);
		when(itemProcessor.disposeOneItem(any(), any(), any(), any(), any()))
			.thenThrow(new RuntimeException("boom"));

		int[] processedCount = new int[1];
		assertThatCode(() -> processedCount[0] = service.disposeExpiredItems()).doesNotThrowAnyException();

		assertThat(processedCount[0]).isZero();
	}

	@Test
	void distinguishesSkippedAndFailedInSummaryLog() {
		AutoDisposeCandidate processed1 = candidate(1L);
		AutoDisposeCandidate processed2 = candidate(2L);
		AutoDisposeCandidate skipped = candidate(3L);
		AutoDisposeCandidate failed1 = candidate(4L);
		AutoDisposeCandidate failed2 = candidate(5L);
		AutoDisposeCandidate failed3 = candidate(6L);
		givenCandidates(processed1, processed2, skipped, failed1, failed2, failed3);
		when(itemProcessor.disposeOneItem(eq(processed1), any(), any(), any(), any())).thenReturn(true);
		when(itemProcessor.disposeOneItem(eq(processed2), any(), any(), any(), any())).thenReturn(true);
		when(itemProcessor.disposeOneItem(eq(skipped), any(), any(), any(), any())).thenReturn(false);
		when(itemProcessor.disposeOneItem(eq(failed1), any(), any(), any(), any()))
			.thenThrow(new RuntimeException("boom1"));
		when(itemProcessor.disposeOneItem(eq(failed2), any(), any(), any(), any()))
			.thenThrow(new RuntimeException("boom2"));
		when(itemProcessor.disposeOneItem(eq(failed3), any(), any(), any(), any()))
			.thenThrow(new RuntimeException("boom3"));

		int processedCount = service.disposeExpiredItems();

		assertThat(processedCount).isEqualTo(2);
		// 정확한 문구가 아니라 처리/스킵/실패 건수(2/1/3)가 로그에 들어 있는지만 느슨하게 확인한다.
		String combined = appender.list.stream()
			.map(ILoggingEvent::getFormattedMessage)
			.reduce("", (a, b) -> a + "\n" + b);
		assertThat(combined).contains("2").contains("1").contains("3");
	}

	@Test
	void returnsCountOfOnlyTrueResultsAcrossMixedOutcomes() {
		AutoDisposeCandidate processed1 = candidate(1L);
		AutoDisposeCandidate skipped = candidate(2L);
		AutoDisposeCandidate failed = candidate(3L);
		AutoDisposeCandidate processed2 = candidate(4L);
		givenCandidates(processed1, skipped, failed, processed2);
		when(itemProcessor.disposeOneItem(eq(processed1), any(), any(), any(), any())).thenReturn(true);
		when(itemProcessor.disposeOneItem(eq(skipped), any(), any(), any(), any())).thenReturn(false);
		when(itemProcessor.disposeOneItem(eq(failed), any(), any(), any(), any()))
			.thenThrow(new RuntimeException("boom"));
		when(itemProcessor.disposeOneItem(eq(processed2), any(), any(), any(), any())).thenReturn(true);

		int processedCount = service.disposeExpiredItems();

		assertThat(processedCount).isEqualTo(2);
	}
}
