package com.vium.dashboard.service;

import com.vium.dashboard.dto.DashboardResponse;
import com.vium.dashboard.dto.ThisMonthDto;
import com.vium.dashboard.repository.DashboardQueryRepository;
import com.vium.user.service.UserProfileService;
import com.vium.user.service.UserSettingsService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardService {

	private static final String EVENT_CONSUMED = "consumed";
	private static final String EVENT_DISPOSED = "disposed";
	private static final String EVENT_SHARED = "shared";

	private final DashboardQueryRepository dashboardQueryRepository;
	private final UserSettingsService userSettingsService;
	private final UserProfileService userProfileService;
	private final Clock clock;

	public DashboardResponse getDashboard(Long userId) {
		LocalDate today = LocalDate.now(clock);
		LocalDate monthStart = today.withDayOfMonth(1);
		LocalDateTime start = monthStart.atStartOfDay();
		LocalDateTime end = monthStart.plusMonths(1).atStartOfDay();

		ThisMonthDto thisMonth = buildThisMonth(userId, today, start, end);

		long totalSavedAmount = dashboardQueryRepository.sumAmountByEventType(userId, EVENT_CONSUMED)
			+ dashboardQueryRepository.sumAmountByEventType(userId, EVENT_SHARED);

		LocalDate lastDisposedOn = dashboardQueryRepository.findLastDisposedAt(userId)
			.map(LocalDateTime::toLocalDate)
			.orElse(null);

		int savingStreakDays = calculateStreak(userId, today, lastDisposedOn);

		int expiryAlertDays = userSettingsService.getExpiryAlertDays(userId);
		int expiringSoonCount = dashboardQueryRepository.countExpiringSoon(userId, today.plusDays(expiryAlertDays));
		int activeIngredientCount = dashboardQueryRepository.countActiveIngredients(userId);

		return new DashboardResponse(thisMonth, totalSavedAmount, savingStreakDays, lastDisposedOn,
			activeIngredientCount, expiringSoonCount);
	}

	private ThisMonthDto buildThisMonth(Long userId, LocalDate today, LocalDateTime start, LocalDateTime end) {
		int[] counts = dashboardQueryRepository.countItems(userId, start, end);
		int consumedCount = counts[0] - counts[1];
		int wastedCount = counts[1];
		long savedAmount = dashboardQueryRepository.sumAmountByEventTypeInRange(userId, EVENT_CONSUMED, start, end)
			+ dashboardQueryRepository.sumAmountByEventTypeInRange(userId, EVENT_SHARED, start, end);
		long wastedAmount = dashboardQueryRepository.sumAmountByEventTypeInRange(userId, EVENT_DISPOSED, start, end);
		int sharedCount = dashboardQueryRepository.countDistinctItemsByEventTypeInRange(
			userId, EVENT_SHARED, start, end);
		return new ThisMonthDto(YearMonth.from(today).toString(), savedAmount, consumedCount, sharedCount,
			wastedAmount, wastedCount);
	}

	/**
	 * 마지막 폐기 다음 날부터 오늘까지 일수(오늘-lastDisposedOn). 폐기 기록이 없으면 가입일부터
	 * "오늘-가입일+1"로 센다(가입 당일을 1일로 보는 제안값, PR에서 확정 필요).
	 */
	private int calculateStreak(Long userId, LocalDate today, LocalDate lastDisposedOn) {
		if (lastDisposedOn != null) {
			return (int) ChronoUnit.DAYS.between(lastDisposedOn, today);
		}
		LocalDateTime createdAtUtc = userProfileService.getCreatedAt(userId);
		LocalDate joinDate = createdAtUtc.toInstant(ZoneOffset.UTC).atZone(clock.getZone()).toLocalDate();
		return (int) ChronoUnit.DAYS.between(joinDate, today) + 1;
	}
}
