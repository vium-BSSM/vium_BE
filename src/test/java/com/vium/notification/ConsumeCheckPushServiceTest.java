package com.vium.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vium.notification.dto.ConsumeCheckPushCandidate;
import com.vium.notification.entity.Notification;
import com.vium.notification.repository.ConsumeCheckPushQueryRepository;
import com.vium.notification.repository.NotificationRepository;
import com.vium.notification.service.ConsumeCheckPushService;
import com.vium.notification.service.PushDispatchService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

class ConsumeCheckPushServiceTest {

	private static final String TITLE = "소진 확인";
	private static final Map<String, String> DATA = Map.of("type", "consume_check");

	private final Clock clock = Clock.fixed(Instant.parse("2026-11-25T11:00:00Z"), ZoneId.of("Asia/Seoul"));
	private final ConsumeCheckPushQueryRepository candidates = mock(ConsumeCheckPushQueryRepository.class);
	private final NotificationRepository notifications = mock(NotificationRepository.class);
	private final PushDispatchService dispatch = mock(PushDispatchService.class);
	private final Map<Long, Notification> saved = new HashMap<>();
	private ConsumeCheckPushService service;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		ObjectProvider<ConsumeCheckPushService> self = mock(ObjectProvider.class);
		service = new ConsumeCheckPushService(candidates, notifications, dispatch, clock, self);
		when(self.getObject()).thenReturn(service);
		when(notifications.saveAndFlush(any(Notification.class))).thenAnswer(invocation -> {
			Notification notification = invocation.getArgument(0);
			Long id = notification.getInventoryItemId();
			ReflectionTestUtils.setField(notification, "id", id);
			saved.put(id, notification);
			return notification;
		});
		when(notifications.findAllById(anyList())).thenAnswer(invocation -> {
			List<Long> ids = invocation.getArgument(0);
			return ids.stream().map(saved::get).toList();
		});
	}

	@Test
	void sendsSingleItemBodyAndOnlyTypeInData() {
		givenCandidates(item(1, 11, "우유"));
		when(dispatch.sendToUser(1L, TITLE, "우유, 다 드셨어요?", DATA)).thenReturn(true);

		assertThat(service.sendDailyConsumeCheckPush()).isEqualTo(1);
		verify(dispatch).sendToUser(1L, TITLE, "우유, 다 드셨어요?", DATA);
		assertThat(saved.get(11L).getBody()).isEqualTo("우유, 다 드셨어요?");
		assertThat(saved.get(11L).getSentAt()).isEqualTo(LocalDateTime.now(clock));
	}

	@Test
	void groupsMultipleItemsIntoOnePush() {
		givenCandidates(item(1, 11, "우유"), item(1, 12, "오이"));
		when(dispatch.sendToUser(1L, TITLE, "오늘까지인 재료 2개, 다 드셨나요?", DATA)).thenReturn(true);

		assertThat(service.sendDailyConsumeCheckPush()).isEqualTo(1);
		verify(dispatch).sendToUser(1L, TITLE, "오늘까지인 재료 2개, 다 드셨나요?", DATA);
		assertThat(saved.values()).allSatisfy(row -> assertThat(row.getSentAt()).isNotNull());
	}

	@Test
	void leavesSentAtNullWhenDispatchFails() {
		givenCandidates(item(1, 11, "우유"));

		assertThat(service.sendDailyConsumeCheckPush()).isZero();
		verify(dispatch).sendToUser(1L, TITLE, "우유, 다 드셨어요?", DATA);
		assertThat(saved.get(11L).getSentAt()).isNull();
	}

	@Test
	void continuesToNextUserAfterDispatchThrows() {
		givenCandidates(item(1, 11, "우유"), item(2, 21, "오이"));
		when(dispatch.sendToUser(eq(1L), any(), any(), any())).thenThrow(new IllegalStateException("failed"));
		when(dispatch.sendToUser(2L, TITLE, "오이, 다 드셨어요?", DATA)).thenReturn(true);

		assertThat(service.sendDailyConsumeCheckPush()).isEqualTo(1);
		verify(dispatch).sendToUser(2L, TITLE, "오이, 다 드셨어요?", DATA);
		assertThat(saved.get(11L).getSentAt()).isNull();
		assertThat(saved.get(21L).getSentAt()).isNotNull();
	}

	@Test
	void skipsConcurrentInsertAndBuildsSingleBodyFromTheRemainingItem() {
		givenCandidates(item(1, 11, "우유"), item(1, 12, "오이"));
		when(notifications.saveAndFlush(argThat(row -> row.getInventoryItemId() == 11L)))
			.thenThrow(new DataIntegrityViolationException("duplicate"));
		when(dispatch.sendToUser(1L, TITLE, "오이, 다 드셨어요?", DATA)).thenReturn(true);

		assertThat(service.sendDailyConsumeCheckPush()).isEqualTo(1);
		verify(dispatch).sendToUser(1L, TITLE, "오이, 다 드셨어요?", DATA);
		assertThat(saved.keySet()).containsExactly(12L);
	}

	private void givenCandidates(ConsumeCheckPushCandidate... items) {
		when(candidates.findCandidates(LocalDate.now(clock))).thenReturn(List.of(items));
	}

	private ConsumeCheckPushCandidate item(long userId, long inventoryItemId, String name) {
		return new ConsumeCheckPushCandidate(userId, inventoryItemId, name, null);
	}
}
