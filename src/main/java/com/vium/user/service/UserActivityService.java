package com.vium.user.service;

import com.vium.user.dto.UserActivityCounts;
import com.vium.user.repository.UserActivityQueryRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 로그인·토큰 갱신으로 세션이 발급된 사용자를 활성 사용자로 집계한다.
 * Asia/Seoul 달력 경계를 UTC로 변환하여 UTC로 저장된 시각과 비교한다.
 * 집계 창은 Refresh Token 수명 + 세션 정리 보존 기간(현재 44일)을 넘으면 안 된다.
 */
@Service
@RequiredArgsConstructor
public class UserActivityService {
	private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

	private final UserActivityQueryRepository userActivityQueryRepository;
	private final Clock authClock;

	public UserActivityCounts currentCounts() {
		LocalDate today = LocalDate.ofInstant(authClock.instant(), SERVICE_ZONE);
		return userActivityQueryRepository.count(startOfDayUtc(today), startOfDayUtc(today.minusDays(6)));
	}

	private static LocalDateTime startOfDayUtc(LocalDate date) {
		return LocalDateTime.ofInstant(date.atStartOfDay(SERVICE_ZONE).toInstant(), ZoneOffset.UTC);
	}
}
