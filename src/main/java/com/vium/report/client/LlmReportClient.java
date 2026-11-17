package com.vium.report.client;

import com.vium.report.dto.LlmReportResponse;
import com.vium.report.dto.MonthlyWasteStats;

public interface LlmReportClient {

	/**
	 * LLM을 호출하여 월간 소진·폐기 집계 통계로 낭비 리포트를 생성합니다.
	 *
	 * @param stats 사용자 식별 정보가 없는 월간 집계 통계
	 * @return 요약·인사이트·다음 장보기 제안
	 * @throws RuntimeException 타임아웃, 네트워크 오류, 파싱/검증 실패 등
	 */
	LlmReportResponse generateReport(MonthlyWasteStats stats);
}
