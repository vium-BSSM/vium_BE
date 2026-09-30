package com.vium.recipe.client;

import com.vium.recipe.dto.ImageSearchResult;

public interface ImageSearchClient {

	/**
	 * 이미지 검색 API에서 사진 정보를 조회합니다.
	 *
	 * @param query 검색어 (영문)
	 * @return 첫 번째 검색 결과의 이미지 정보, 또는 결과 없으면 null
	 */
	ImageSearchResult searchImage(String query);
}
