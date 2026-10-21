package com.vium.dispose.service;

import com.vium.dispose.dto.GetWastePatternsResponse;
import com.vium.dispose.dto.GetWasteReportsResponse;
import com.vium.dispose.dto.WasteReportDto;
import com.vium.dispose.repository.WasteReportQueryRepository;
import java.time.LocalDateTime;
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
		List<WasteReportDto> reports = wasteReportQueryRepository.findReports(userId).stream()
			.map(report -> withSummary(userId, report))
			.toList();
		return new GetWasteReportsResponse(reports);
	}

	private WasteReportDto withSummary(Long userId, WasteReportDto report) {
		LocalDateTime start = report.periodStart().atStartOfDay();
		LocalDateTime end = report.periodEnd().plusDays(1).atStartOfDay();
		int[] counts = wasteReportQueryRepository.countItems(userId, start, end);
		return report.withSummary(counts[0], counts[0] - counts[1], counts[1],
			wasteReportQueryRepository.findTopWastedItems(userId, start, end));
	}

	public GetWastePatternsResponse getPatterns(Long userId) {
		return new GetWastePatternsResponse(wasteReportQueryRepository.findPatterns(userId));
	}
}
