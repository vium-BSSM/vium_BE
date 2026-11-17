package com.vium.safety.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "user_reports")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserReport {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "reporter_id", nullable = false)
	private Long reporterId;

	@Enumerated(EnumType.STRING)
	@Column(name = "target_type", nullable = false, length = 10)
	private ReportTargetType targetType;

	@Column(name = "target_user_id")
	private Long targetUserId;

	@Column(name = "target_post_id")
	private Long targetPostId;

	@Enumerated(EnumType.STRING)
	@Column(name = "reason", nullable = false, length = 20)
	private ReportReason reason;

	@Column(name = "detail", length = 500)
	private String detail;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private ReportStatus status;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	public static UserReport create(Long reporterId, ReportTargetType targetType, Long targetUserId,
			Long targetPostId, ReportReason reason, String detail, LocalDateTime createdAt) {
		UserReport report = new UserReport();
		report.reporterId = reporterId;
		report.targetType = targetType;
		report.targetUserId = targetUserId;
		report.targetPostId = targetPostId;
		report.reason = reason;
		report.detail = detail;
		report.status = ReportStatus.RECEIVED;
		report.createdAt = createdAt;
		return report;
	}
}
