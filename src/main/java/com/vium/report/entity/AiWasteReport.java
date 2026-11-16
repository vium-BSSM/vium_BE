package com.vium.report.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "ai_waste_reports",
	uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "year_month"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiWasteReport {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "year_month", nullable = false, length = 7)
	private String yearMonth;

	@Column(name = "cost", nullable = false)
	private Integer cost;

	@Column(name = "summary", nullable = false, columnDefinition = "text")
	private String summary;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "insights", nullable = false)
	private String insightsJson;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "suggestions", nullable = false)
	private String suggestionsJson;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	private AiWasteReport(Long userId, String yearMonth, int cost, String summary, String insightsJson,
			String suggestionsJson, LocalDateTime createdAt) {
		this.userId = userId;
		this.yearMonth = yearMonth;
		this.cost = cost;
		this.summary = summary;
		this.insightsJson = insightsJson;
		this.suggestionsJson = suggestionsJson;
		this.createdAt = createdAt;
	}

	public static AiWasteReport create(Long userId, String yearMonth, int cost, String summary, String insightsJson,
			String suggestionsJson, LocalDateTime createdAt) {
		return new AiWasteReport(userId, yearMonth, cost, summary, insightsJson, suggestionsJson, createdAt);
	}
}
