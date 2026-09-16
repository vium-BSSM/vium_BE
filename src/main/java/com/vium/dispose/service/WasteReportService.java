package com.vium.dispose.service;

import com.vium.dispose.dto.GetWastePatternsResponse;
import com.vium.dispose.dto.GetWasteReportsResponse;
import com.vium.dispose.repository.WasteReportQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WasteReportService {

	private final WasteReportQueryRepository wasteReportQueryRepository;

	public GetWasteReportsResponse getReports(Long userId) {
		return new GetWasteReportsResponse(wasteReportQueryRepository.findReports(userId));
	}

	public GetWastePatternsResponse getPatterns(Long userId) {
		return new GetWastePatternsResponse(wasteReportQueryRepository.findPatterns(userId));
	}
}
