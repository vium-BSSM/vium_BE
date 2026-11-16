package com.vium.safety.service;

import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.safety.entity.ReportReason;
import com.vium.safety.entity.ReportTargetType;
import com.vium.safety.entity.UserReport;
import com.vium.safety.repository.SafetyQueryRepository;
import com.vium.safety.repository.UserReportRepository;
import com.vium.user.service.UserProfileService;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class UserReportService {

	private final UserReportRepository userReportRepository;
	private final SafetyQueryRepository safetyQueryRepository;
	private final UserProfileService userProfileService;
	private final Clock clock;

	@Transactional
	public Long createReport(Long reporterId, ReportTargetType targetType, Long targetId, ReportReason reason,
			String detail) {
		if (reason == ReportReason.ETC && !StringUtils.hasText(detail)) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, "reason이 ETC면 detail이 필요합니다");
		}
		return targetType == ReportTargetType.USER
			? reportUser(reporterId, targetId, reason, detail)
			: reportPost(reporterId, targetId, reason, detail);
	}

	private Long reportUser(Long reporterId, Long targetUserId, ReportReason reason, String detail) {
		if (targetUserId.equals(reporterId)) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, "자기 자신을 신고할 수 없습니다");
		}
		userProfileService.requireActiveUser(targetUserId);
		if (userReportRepository.existsByReporterIdAndTargetTypeAndTargetUserId(
				reporterId, ReportTargetType.USER, targetUserId)) {
			throw new BusinessException(ErrorCode.ALREADY_REPORTED);
		}
		return save(reporterId, ReportTargetType.USER, targetUserId, null, reason, detail);
	}

	private Long reportPost(Long reporterId, Long targetPostId, ReportReason reason, String detail) {
		Long authorId = safetyQueryRepository.findPostAuthorId(targetPostId)
			.orElseThrow(() -> new BusinessException(ErrorCode.POST_NOT_FOUND));
		if (authorId.equals(reporterId)) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, "내 게시물은 신고할 수 없습니다");
		}
		if (userReportRepository.existsByReporterIdAndTargetTypeAndTargetPostId(
				reporterId, ReportTargetType.POST, targetPostId)) {
			throw new BusinessException(ErrorCode.ALREADY_REPORTED);
		}
		return save(reporterId, ReportTargetType.POST, null, targetPostId, reason, detail);
	}

	private Long save(Long reporterId, ReportTargetType targetType, Long targetUserId, Long targetPostId,
			ReportReason reason, String detail) {
		try {
			UserReport saved = userReportRepository.saveAndFlush(UserReport.create(
				reporterId, targetType, targetUserId, targetPostId, reason, detail, LocalDateTime.now(clock)));
			return saved.getId();
		} catch (DataIntegrityViolationException e) {
			throw new BusinessException(ErrorCode.ALREADY_REPORTED);
		}
	}
}
