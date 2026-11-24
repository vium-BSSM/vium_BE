package com.vium.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vium.notification.dto.ExpiryPushCandidate;
import com.vium.notification.entity.Notification;
import com.vium.notification.entity.PushPlatform;
import com.vium.notification.entity.UserPushToken;
import com.vium.notification.repository.ExpiryPushQueryRepository;
import com.vium.notification.repository.NotificationRepository;
import com.vium.notification.repository.UserPushTokenRepository;
import com.vium.notification.service.ExpiryPushService;
import com.vium.notification.service.FcmPushClient;
import com.vium.notification.service.PushDispatchService;
import com.vium.notification.service.PushResult;
import com.vium.notification.service.PushTokenService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;

class ExpiryPushServiceTest {

	private final ExpiryPushQueryRepository expiryPushQueryRepository = mock(ExpiryPushQueryRepository.class);
	private final UserPushTokenRepository userPushTokenRepository = mock(UserPushTokenRepository.class);
	private final NotificationRepository notificationRepository = mock(NotificationRepository.class);
	private final PushTokenService pushTokenService = mock(PushTokenService.class);
	private final FcmPushClient fcmPushClient = mock(FcmPushClient.class);
	private final Clock clock = Clock.fixed(Instant.parse("2026-11-16T00:00:00Z"), ZoneOffset.UTC);

	@SuppressWarnings("unchecked")
	private final ObjectProvider<ExpiryPushService> self = mock(ObjectProvider.class);

	private ExpiryPushService service;

	@BeforeEach
	void setUp() {
		PushDispatchService pushDispatchService =
			new PushDispatchService(userPushTokenRepository, pushTokenService, fcmPushClient);
		service = new ExpiryPushService(
			expiryPushQueryRepository, notificationRepository, pushDispatchService, clock, self);
		when(self.getObject()).thenReturn(service);
	}

	private ExpiryPushCandidate newCandidate(long userId, long itemId, int alertDays) {
		return new ExpiryPushCandidate(userId, itemId, "재료" + itemId,
			java.time.LocalDate.now(clock).plusDays(1), alertDays, null);
	}

	private ExpiryPushCandidate retryCandidate(long userId, long itemId, int alertDays, long existingNotificationId) {
		return new ExpiryPushCandidate(userId, itemId, "재료" + itemId,
			java.time.LocalDate.now(clock).plusDays(1), alertDays, existingNotificationId);
	}

	private UserPushToken token(String value) {
		return UserPushToken.register(1L, value, PushPlatform.ANDROID, LocalDateTime.now(clock));
	}

	@Test
	void sendsNothingWhenNoCandidates() {
		when(expiryPushQueryRepository.findCandidates(any(), any())).thenReturn(List.of());

		int sentUserCount = service.sendDailyExpiryPush();

		assertThat(sentUserCount).isZero();
		verify(fcmPushClient, never()).send(anyString(), anyString(), anyString(), any());
	}

	@Test
	void sendsExactlyOnePushForUserWithMultipleExpiringItems() {
		when(expiryPushQueryRepository.findCandidates(any(), any()))
			.thenReturn(List.of(newCandidate(1L, 10L, 2), newCandidate(1L, 11L, 2)));
		when(notificationRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
		when(userPushTokenRepository.findAllByUserId(1L)).thenReturn(List.of(token("t1")));
		when(fcmPushClient.send(eq("t1"), anyString(), eq("소비기한이 임박한 재료가 2개 있어요"), any()))
			.thenReturn(PushResult.SUCCESS);

		int sentUserCount = service.sendDailyExpiryPush();

		assertThat(sentUserCount).isEqualTo(1);
		verify(fcmPushClient).send(eq("t1"), anyString(), anyString(), any());
	}

	@Test
	void marksSentOnCreatedRowsWhenPushSucceeds() {
		Notification saved = Notification.expiry(1L, 10L, "제목", "본문", LocalDateTime.now(clock), LocalDateTime.now(clock));
		when(expiryPushQueryRepository.findCandidates(any(), any())).thenReturn(List.of(newCandidate(1L, 10L, 2)));
		when(notificationRepository.saveAndFlush(any())).thenReturn(saved);
		when(notificationRepository.findAllById(any())).thenReturn(List.of(saved));
		when(userPushTokenRepository.findAllByUserId(1L)).thenReturn(List.of(token("t1")));
		when(fcmPushClient.send(any(), any(), any(), any())).thenReturn(PushResult.SUCCESS);

		service.sendDailyExpiryPush();

		assertThat(saved.getSentAt()).isNotNull();
	}

	@Test
	void doesNotMarkSentWhenAllTokensFail() {
		Notification saved = Notification.expiry(1L, 10L, "제목", "본문", LocalDateTime.now(clock), LocalDateTime.now(clock));
		when(expiryPushQueryRepository.findCandidates(any(), any())).thenReturn(List.of(newCandidate(1L, 10L, 2)));
		when(notificationRepository.saveAndFlush(any())).thenReturn(saved);
		when(userPushTokenRepository.findAllByUserId(1L)).thenReturn(List.of(token("t1")));
		when(fcmPushClient.send(any(), any(), any(), any())).thenReturn(PushResult.FAILED);

		int sentUserCount = service.sendDailyExpiryPush();

		assertThat(sentUserCount).isZero();
		assertThat(saved.getSentAt()).isNull();
		verify(notificationRepository, never()).findAllById(any());
	}

	@Test
	void deletesTokenWhenFcmReturnsInvalidToken() {
		Notification saved = Notification.expiry(1L, 10L, "제목", "본문", LocalDateTime.now(clock), LocalDateTime.now(clock));
		when(expiryPushQueryRepository.findCandidates(any(), any())).thenReturn(List.of(newCandidate(1L, 10L, 2)));
		when(notificationRepository.saveAndFlush(any())).thenReturn(saved);
		when(userPushTokenRepository.findAllByUserId(1L)).thenReturn(List.of(token("stale-token")));
		when(fcmPushClient.send(eq("stale-token"), any(), any(), any())).thenReturn(PushResult.INVALID_TOKEN);

		service.sendDailyExpiryPush();

		verify(pushTokenService).deleteByToken("stale-token");
	}

	@Test
	void skipsDuplicateItemFromUniqueViolationWithoutAffectingSiblingItem() {
		Notification savedForOk = Notification.expiry(1L, 11L, "제목", "본문", LocalDateTime.now(clock), LocalDateTime.now(clock));
		when(expiryPushQueryRepository.findCandidates(any(), any()))
			.thenReturn(List.of(newCandidate(1L, 10L, 2), newCandidate(1L, 11L, 2)));
		when(notificationRepository.saveAndFlush(any())).thenAnswer(inv -> {
			Notification n = inv.getArgument(0);
			if (n.getInventoryItemId().equals(10L)) {
				throw new DataIntegrityViolationException("이미 처리됨(테스트)");
			}
			return savedForOk;
		});
		when(notificationRepository.findAllById(any())).thenReturn(List.of(savedForOk));
		when(userPushTokenRepository.findAllByUserId(1L)).thenReturn(List.of(token("t1")));
		when(fcmPushClient.send(any(), any(), eq("소비기한이 임박한 재료가 1개 있어요"), any()))
			.thenReturn(PushResult.SUCCESS);

		int sentUserCount = service.sendDailyExpiryPush();

		assertThat(sentUserCount).isEqualTo(1);
		assertThat(savedForOk.getSentAt()).isNotNull();
	}

	@Test
	void oneUsersFailureDoesNotAffectOtherUsers() {
		when(expiryPushQueryRepository.findCandidates(any(), any()))
			.thenReturn(List.of(newCandidate(1L, 10L, 2), newCandidate(2L, 20L, 2)));
		when(notificationRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
		when(userPushTokenRepository.findAllByUserId(1L)).thenThrow(new RuntimeException("boom"));
		when(userPushTokenRepository.findAllByUserId(2L)).thenReturn(List.of(token("t2")));
		when(fcmPushClient.send(eq("t2"), any(), any(), any())).thenReturn(PushResult.SUCCESS);

		int sentUserCount = service.sendDailyExpiryPush();

		assertThat(sentUserCount).isEqualTo(1);
		verify(fcmPushClient).send(eq("t2"), any(), any(), any());
	}

	@Test
	void doesNotCallFcmWhenUserHasNoToken() {
		when(expiryPushQueryRepository.findCandidates(any(), any())).thenReturn(List.of(newCandidate(1L, 10L, 2)));
		when(notificationRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
		when(userPushTokenRepository.findAllByUserId(1L)).thenReturn(List.of());

		int sentUserCount = service.sendDailyExpiryPush();

		assertThat(sentUserCount).isZero();
		verify(fcmPushClient, never()).send(any(), any(), any(), any());
	}

	@Test
	void pushDataPayloadOnlyContainsType() {
		when(expiryPushQueryRepository.findCandidates(any(), any())).thenReturn(List.of(newCandidate(1L, 10L, 2)));
		when(notificationRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
		when(userPushTokenRepository.findAllByUserId(1L)).thenReturn(List.of(token("t1")));
		when(fcmPushClient.send(any(), any(), any(), any())).thenReturn(PushResult.SUCCESS);

		service.sendDailyExpiryPush();

		verify(fcmPushClient).send(eq("t1"), any(), any(), eq(Map.of("type", "expiry")));
	}

	@Test
	void retryCandidateReusesExistingRowWithoutInsertingNewOne() {
		Notification existing = Notification.expiry(1L, 10L, "제목", "본문", LocalDateTime.now(clock), LocalDateTime.now(clock));
		when(expiryPushQueryRepository.findCandidates(any(), any()))
			.thenReturn(List.of(retryCandidate(1L, 10L, 2, 99L)));
		when(notificationRepository.findAllById(List.of(99L))).thenReturn(List.of(existing));
		when(userPushTokenRepository.findAllByUserId(1L)).thenReturn(List.of(token("t1")));
		when(fcmPushClient.send(any(), any(), any(), any())).thenReturn(PushResult.SUCCESS);

		int sentUserCount = service.sendDailyExpiryPush();

		assertThat(sentUserCount).isEqualTo(1);
		verify(notificationRepository, never()).saveAndFlush(any());
		assertThat(existing.getSentAt()).isNotNull();
	}

	@Test
	void summaryCountsNewAndRetryItemsTogether() {
		when(expiryPushQueryRepository.findCandidates(any(), any()))
			.thenReturn(List.of(newCandidate(1L, 10L, 2), retryCandidate(1L, 11L, 2, 55L)));
		when(notificationRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
		when(userPushTokenRepository.findAllByUserId(1L)).thenReturn(List.of(token("t1")));
		when(fcmPushClient.send(any(), any(), eq("소비기한이 임박한 재료가 2개 있어요"), any()))
			.thenReturn(PushResult.SUCCESS);

		int sentUserCount = service.sendDailyExpiryPush();

		assertThat(sentUserCount).isEqualTo(1);
		verify(fcmPushClient).send(any(), any(), eq("소비기한이 임박한 재료가 2개 있어요"), any());
	}
}
