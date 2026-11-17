package com.vium.safety.repository;

import com.vium.safety.entity.ReportTargetType;
import com.vium.safety.entity.UserReport;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserReportRepository extends JpaRepository<UserReport, Long> {

	boolean existsByReporterIdAndTargetTypeAndTargetUserId(Long reporterId, ReportTargetType targetType,
			Long targetUserId);

	boolean existsByReporterIdAndTargetTypeAndTargetPostId(Long reporterId, ReportTargetType targetType,
			Long targetPostId);
}
