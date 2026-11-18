package com.vium.dispose.service;

import com.vium.dispose.dto.GetWastePatternsResponse;
import com.vium.dispose.dto.GetWasteReportsResponse;
import com.vium.dispose.dto.WasteReportDto;
import com.vium.dispose.repository.WasteReportQueryRepository;
import com.vium.global.exception.InvalidRequestException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WasteReportService {

	private final WasteReportQueryRepository wasteReportQueryRepository;

	public GetWasteReportsResponse getReports(Long userId) {
		return getReports(userId, null);
	}

	public GetWasteReportsResponse getReports(Long userId, String yearMonth) {
		if (yearMonth == null) {
			List<WasteReportDto> reports = wasteReportQueryRepository.findReports(userId).stream()
				.map(report -> withSummary(userId, report))
				.toList();
			return new GetWasteReportsResponse(reports);
		}

		YearMonth parsed = parseYearMonth(yearMonth);
		LocalDate periodStart = parsed.atDay(1);
		LocalDate periodEnd = parsed.atEndOfMonth();
		LocalDateTime start = periodStart.atStartOfDay();
		LocalDateTime end = periodEnd.plusDays(1).atStartOfDay();

		if (!wasteReportQueryRepository.hasAnyEvents(userId, start, end)) {
			return new GetWasteReportsResponse(List.of());
		}

		long wasteReportId = parsed.getYear() * 100L + parsed.getMonthValue();
		long totalWastedAmount = wasteReportQueryRepository.sumDisposedAmount(userId, start, end);
		WasteReportDto report = new WasteReportDto(wasteReportId, periodStart, periodEnd, totalWastedAmount, 0L);
		return new GetWasteReportsResponse(List.of(withSummary(userId, report)));
	}

	private YearMonth parseYearMonth(String yearMonth) {
		try {
			return YearMonth.parse(yearMonth);
		} catch (DateTimeParseException e) {
			throw new InvalidRequestException("yearMonth는 YYYY-MM 형식이어야 합니다");
		}
	}

	private WasteReportDto withSummary(Long userId, WasteReportDto report) {
		LocalDateTime start = report.periodStart().atStartOfDay();
		LocalDateTime end = report.periodEnd().plusDays(1).atStartOfDay();
		int[] counts = wasteReportQueryRepository.countItems(userId, start, end);
		long purchaseTotalAmount = wasteReportQueryRepository.sumPurchaseTotalAmount(userId, start, end);
		int sharedCount = wasteReportQueryRepository.countSharedItems(userId, start, end);
		long sharedAmount = wasteReportQueryRepository.sumSharedAmount(userId, start, end);
		return report.withSummary(counts[0], counts[0] - counts[1], counts[1],
				wasteReportQueryRepository.findTopWastedItems(userId, start, end))
			.withExtras(purchaseTotalAmount, sharedCount, sharedAmount);
	}

	public GetWastePatternsResponse getPatterns(Long userId) {
		return new GetWastePatternsResponse(wasteReportQueryRepository.findPatterns(userId));
	}
}
