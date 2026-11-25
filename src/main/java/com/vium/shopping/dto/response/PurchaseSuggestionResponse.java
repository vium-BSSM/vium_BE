package com.vium.shopping.dto.response;

import java.math.BigDecimal;
import java.util.List;

public record PurchaseSuggestionResponse(
	boolean available,
	String basedOnPeriodStart,
	String basedOnPeriodEnd,
	List<Item> items
) {

	public record Item(
		Long ingredientCatalogId,
		String ingredientName,
		Short unitId,
		String unit,
		BigDecimal consumedQuantity,
		BigDecimal disposedQuantity,
		BigDecimal currentQuantity,
		BigDecimal wasteRatio,
		BigDecimal suggestedQuantity,
		Adjustment adjustment,
		String message
	) {
	}

	public enum Adjustment {
		HAVE_ENOUGH("아직 집에 있어요. 이번엔 안 사도 괜찮아요"),
		SKIP("지난번엔 거의 다 남았어요. 이번엔 건너뛰어도 괜찮아요"),
		REDUCE_HALF("지난번엔 절반 넘게 남았어요. 이번엔 절반만 사 보세요"),
		REDUCE_SOME("지난번엔 조금 남았어요. 이번엔 조금 덜 사 보세요"),
		KEEP("지난번 양이 잘 맞았어요");

		private final String message;

		Adjustment(String message) {
			this.message = message;
		}

		public String message() {
			return message;
		}
	}
}
