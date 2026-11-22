package com.vium.recipe.service;

import com.vium.recipe.client.ImageSearchClient;
import com.vium.recipe.dto.ImageSearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 레시피 이미지를 검색어로 찾는다. 검색 결과가 없으면 null을 돌려주며,
 * 카테고리 대체 검색어로 재검색하지 않는다(포괄적인 대체어가 요리와 무관한 사진을 반환하는 문제를 막기 위함).
 * 사진을 쓰기로 정하면 다운로드 기록 API를 호출한다(Unsplash 약관).
 * 검색 실패는 예외 없이 null로 돌려준다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RecipeImageFinder {

	private final ImageSearchClient imageSearchClient;

	public ImageSearchResult find(String keyword) {
		try {
			ImageSearchResult result = imageSearchClient.searchImage(keyword);
			if (result != null) {
				try {
					imageSearchClient.recordDownload(result.downloadUrl());
				} catch (Exception e) {
					log.debug("다운로드 기록 호출 실패 (무시): {}", e.getMessage());
				}
			}
			return result;
		} catch (Exception e) {
			log.debug("이미지 검색 실패 (무시): {}", e.getMessage());
			return null;
		}
	}
}
