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

	/**
	 * 다운로드 기록을 호출합니다 (Unsplash 약관 필수).
	 *
	 * @param downloadUrl Unsplash가 제공한 다운로드 기록 URL
	 */
	void recordDownload(String downloadUrl);
}
