package com.vium.recipe.service;

import com.vium.recipe.client.ImageSearchClient;
import com.vium.recipe.dto.ImageSearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 레시피 이미지를 검색어로 찾고, 결과가 없으면 카테고리 대체 검색어로 한 번 더 찾는다.
 * 사진을 쓰기로 정하면 다운로드 기록 API를 호출한다(Unsplash 약관).
 * 검색 실패는 예외 없이 null로 돌려준다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RecipeImageFinder {

	private final ImageSearchClient imageSearchClient;

	public ImageSearchResult find(String keyword, String category) {
		try {
			ImageSearchResult result = imageSearchClient.searchImage(keyword);
			if (result == null) {
				result = imageSearchClient.searchImage(fallbackKeyword(category));
			}
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

	static String fallbackKeyword(String category) {
		if (category == null) {
			return "food";
		}
		return switch (category) {
			case "KOREAN" -> "korean food";
			case "CHINESE" -> "chinese food";
			case "WESTERN" -> "western food";
			case "JAPANESE" -> "japanese food";
			case "DESSERT" -> "dessert";
			default -> "food";
		};
	}
}
