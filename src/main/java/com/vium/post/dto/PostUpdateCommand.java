package com.vium.post.dto;

import java.util.List;
import java.util.Optional;

/**
 * PATCH 요청의 "보낸 필드만 바꾼다"를 표현한다. Optional.empty()는 생략, 값이 있으면 보낸 필드.
 * JSON null은 컨트롤러에서 걸러 400으로 응답하므로 여기까지 오지 않는다.
 */
public record PostUpdateCommand(
	Optional<String> title,
	Optional<String> description,
	Optional<Integer> price,
	Optional<List<Long>> imageIds
) {

	public boolean isEmpty() {
		return title.isEmpty() && description.isEmpty() && price.isEmpty() && imageIds.isEmpty();
	}
}
