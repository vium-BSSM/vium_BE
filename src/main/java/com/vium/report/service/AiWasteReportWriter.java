package com.vium.report.service;

import com.vium.credit.entity.CreditTransactionType;
import com.vium.credit.service.CreditService;
import com.vium.report.entity.AiWasteReport;
import com.vium.report.repository.AiWasteReportRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 리포트 저장과 크레딧 차감을 한 트랜잭션으로 묶는다. (user_id, year_month) 유니크 위반은 이 메서드를 호출한
 * 트랜잭션 밖(AiWasteReportService)에서 잡아 기존 리포트를 반환해야 한다. 이 클래스 안에서는 예외를 삼키지 않는다.
 */
@Service
@RequiredArgsConstructor
public class AiWasteReportWriter {

	private final AiWasteReportRepository aiWasteReportRepository;
	private final CreditService creditService;

	@Transactional
	public AiWasteReport saveAndDeduct(Long userId, String yearMonth, int cost, String summary,
			String insightsJson, String suggestionsJson, LocalDateTime createdAt) {
		AiWasteReport report = aiWasteReportRepository.saveAndFlush(
			AiWasteReport.create(userId, yearMonth, cost, summary, insightsJson, suggestionsJson, createdAt));
		creditService.deduct(userId, cost, CreditTransactionType.AI_REPORT, report.getId());
		return report;
	}
}
