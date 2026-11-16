package com.vium.post.dto;

import com.vium.post.entity.PostType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record PostCreateRequest(
	@NotNull Long inventoryItemId,
	@NotNull PostType type,
	@NotBlank String title,
	@Size(max = 1000) String description,
	Integer price,
	@Size(max = 5) List<Long> imageIds,
	@NotNull Boolean isOpened,
	@NotNull Short storageMethodId,
	@NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
	@NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude
) {
}
