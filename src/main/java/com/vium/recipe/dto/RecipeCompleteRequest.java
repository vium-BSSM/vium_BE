package com.vium.recipe.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

public record RecipeCompleteRequest(
	@NotEmpty(message = "사용 재료 목록이 비어있습니다.")
	@Size(max = 50, message = "사용 재료는 최대 50개입니다.")
	List<UsageDto> usages
) {

	public record UsageDto(
		@NotNull(message = "재고 ID는 필수입니다.")
		Long inventoryId,
		@Min(value = 0, message = "사용률은 0 이상이어야 합니다.")
		@Max(value = 100, message = "사용률은 100 이하여야 합니다.")
		Integer usageRate,
		@DecimalMin(value = "0", message = "사용 수량은 0 이상이어야 합니다.")
		BigDecimal usedQuantity,
		@DecimalMin(value = "0", message = "폐기 수량은 0 이상이어야 합니다.")
		BigDecimal wasteQuantity,
		@Min(value = 0, message = "폐기 금액은 0 이상이어야 합니다.")
		Integer wasteAmount
	) {}
}
