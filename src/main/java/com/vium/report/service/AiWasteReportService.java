package com.vium.report.service;

import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.global.exception.NotFoundException;
import com.vium.report.client.LlmReportClient;
import com.vium.report.config.AiReportProperties;
import com.vium.report.dto.AiWasteReportResponse;
import com.vium.report.dto.AiWasteReportSummaryDto;
import com.vium.report.dto.CreateAiWasteReportRequest;
import com.vium.report.dto.CreateAiWasteReportResponse;
import com.vium.report.dto.GetAiWasteReportsResponse;
import com.vium.report.dto.InsightDto;
import com.vium.report.dto.LlmReportResponse;
import com.vium.report.dto.MonthlyWasteStats;
import com.vium.report.entity.AiWasteReport;
import com.vium.report.repository.AiWasteReportRepository;
import com.vium.report.repository.ReportStatsQueryRepository;
import com.vium.credit.service.CreditService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * LLM 호출(10초 이상 걸릴 수 있음)은 트랜잭션 밖에서 수행한다. 리포트 저장 + 크레딧 차감만
 * {@link AiWasteReportWriter}에서 한 트랜잭션으로 묶는다. 이 클래스 전체에는 @Transactional을 걸지 않는다.
 */
@Service
@RequiredArgsConstructor
public class AiWasteReportService {

	private static final Pattern YEAR_MONTH_PATTERN = Pattern.compile("^\\d{4}-\\d{2}$");
	private static final DateTimeFormatter YEAR_MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

	private final AiWasteReportRepository aiWasteReportRepository;
	private final ReportStatsQueryRepository reportStatsQueryRepository;
	private final CreditService creditService;
	private final LlmReportClient llmReportClient;
	private final AiWasteReportWriter aiWasteReportWriter;
	private final AiReportProperties properties;
	private final JsonMapper jsonMapper;
	private final Clock clock;

	public CreateAiWasteReportResponse create(Long userId, CreateAiWasteReportRequest request) {
		YearMonth yearMonth = parseYearMonth(request == null ? null : request.yearMonth());
		String yearMonthCode = yearMonth.format(YEAR_MONTH_FORMATTER);

		var existing = aiWasteReportRepository.findByUserIdAndYearMonth(userId, yearMonthCode);
		if (existing.isPresent()) {
			return toCreateResponse(existing.get(), 0);
		}

		LocalDateTime start = yearMonth.atDay(1).atStartOfDay();
		LocalDateTime end = yearMonth.plusMonths(1).atDay(1).atStartOfDay();
		MonthlyWasteStats stats = reportStatsQueryRepository.aggregate(userId, start, end);
		if (stats.isEmpty()) {
			throw new BusinessException(ErrorCode.NOT_ENOUGH_DATA);
		}

		int cost = properties.cost();
		if (creditService.getAvailableBalance(userId) < cost) {
			throw new BusinessException(ErrorCode.INSUFFICIENT_CREDIT);
		}

		LlmReportResponse llmResponse = callLlmWithRetry(stats);

		try {
			AiWasteReport saved = aiWasteReportWriter.saveAndDeduct(userId, yearMonthCode, cost,
				llmResponse.summary(), toJson(llmResponse.insights()), toJson(llmResponse.suggestions()),
				LocalDateTime.now(clock));
			return toCreateResponse(saved, cost);
		} catch (DataIntegrityViolationException concurrentDuplicate) {
			AiWasteReport report = aiWasteReportRepository.findByUserIdAndYearMonth(userId, yearMonthCode)
				.orElseThrow(() -> new BusinessException(ErrorCode.AI_REPORT_GENERATION_FAILED));
			return toCreateResponse(report, 0);
		}
	}

	@Transactional(readOnly = true)
	public GetAiWasteReportsResponse getReports(Long userId) {
		List<AiWasteReportSummaryDto> reports = aiWasteReportRepository.findByUserIdOrderByYearMonthDesc(userId)
			.stream()
			.map(r -> new AiWasteReportSummaryDto(r.getId(), r.getYearMonth(), toOffsetDateTime(r.getCreatedAt())))
			.toList();
		return new GetAiWasteReportsResponse(reports);
	}

	@Transactional(readOnly = true)
	public AiWasteReportResponse getReport(Long userId, Long aiReportId) {
		AiWasteReport report = aiWasteReportRepository.findByIdAndUserId(aiReportId, userId)
			.orElseThrow(() -> new NotFoundException("리포트를 찾을 수 없습니다"));
		return toResponse(report);
	}

	private YearMonth parseYearMonth(String raw) {
		if (raw == null || !YEAR_MONTH_PATTERN.matcher(raw).matches()) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, "yearMonth 형식이 올바르지 않습니다");
		}
		YearMonth yearMonth;
		try {
			yearMonth = YearMonth.parse(raw, YEAR_MONTH_FORMATTER);
		} catch (DateTimeParseException e) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, "yearMonth 형식이 올바르지 않습니다");
		}
		if (yearMonth.isAfter(YearMonth.now(clock))) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, "미래 월은 요청할 수 없습니다");
		}
		return yearMonth;
	}

	private LlmReportResponse callLlmWithRetry(MonthlyWasteStats stats) {
		try {
			return llmReportClient.generateReport(stats);
		} catch (Exception firstAttemptFailure) {
			try {
				return llmReportClient.generateReport(stats);
			} catch (Exception secondAttemptFailure) {
				throw new BusinessException(ErrorCode.AI_REPORT_GENERATION_FAILED);
			}
		}
	}

	private String toJson(Object value) {
		return jsonMapper.writeValueAsString(value);
	}

	private CreateAiWasteReportResponse toCreateResponse(AiWasteReport report, int chargedCredit) {
		return new CreateAiWasteReportResponse(report.getId(), report.getYearMonth(), report.getCost(),
			report.getSummary(), fromJsonInsights(report.getInsightsJson()),
			fromJsonSuggestions(report.getSuggestionsJson()), toOffsetDateTime(report.getCreatedAt()), chargedCredit);
	}

	private AiWasteReportResponse toResponse(AiWasteReport report) {
		return new AiWasteReportResponse(report.getId(), report.getYearMonth(), report.getCost(),
			report.getSummary(), fromJsonInsights(report.getInsightsJson()),
			fromJsonSuggestions(report.getSuggestionsJson()), toOffsetDateTime(report.getCreatedAt()));
	}

	private List<InsightDto> fromJsonInsights(String json) {
		return jsonMapper.readValue(json, new TypeReference<List<InsightDto>>() {});
	}

	private List<String> fromJsonSuggestions(String json) {
		return jsonMapper.readValue(json, new TypeReference<List<String>>() {});
	}

	private OffsetDateTime toOffsetDateTime(LocalDateTime localDateTime) {
		return localDateTime.atZone(clock.getZone()).toOffsetDateTime();
	}
}
