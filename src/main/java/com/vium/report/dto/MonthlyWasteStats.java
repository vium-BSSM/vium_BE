package com.vium.report.dto;

import java.util.List;

/**
 * 한 달치 소진·폐기 집계 통계. LLM 입력으로 그대로 쓰이므로 사용자 ID·이메일·이름을 포함하지 않는다.
 */
public record MonthlyWasteStats(
	int consumedCount,
	int disposedCount,
	long consumedAmount,
	long disposedAmount,
	List<CategoryWaste> categoryWaste,
	List<ItemWaste> itemWaste,
	Double avgDaysPurchaseToDispose) {

	public boolean isEmpty() {
		return consumedCount == 0 && disposedCount == 0;
	}

	public record CategoryWaste(String categoryName, int wasteCount, long wasteAmount) {
	}

	public record ItemWaste(String itemName, int wasteCount, long wasteAmount) {
	}
}
