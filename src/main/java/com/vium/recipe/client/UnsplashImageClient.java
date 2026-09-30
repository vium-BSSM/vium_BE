package com.vium.recipe.client;

import com.vium.recipe.config.ImageSearchProperties;
import com.vium.recipe.dto.ImageSearchResponse;
import com.vium.recipe.dto.ImageSearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@RequiredArgsConstructor
@Slf4j
public class UnsplashImageClient implements ImageSearchClient {

	private final ImageSearchProperties properties;

	private static final String UNSPLASH_API_URL = "https://api.unsplash.com/search/photos";

	@Override
	public ImageSearchResult searchImage(String query) {
		if (query == null || query.isBlank()) {
			return null;
		}

		try {
			RestClient restClient = RestClient.builder().build();

			ImageSearchResponse searchResponse = restClient.get()
				.uri(uriBuilder -> uriBuilder.path(UNSPLASH_API_URL)
					.queryParam("query", query)
					.queryParam("per_page", 1)
					.queryParam("orientation", "landscape")
					.build())
				.header("Authorization", "Client-ID " + properties.getAccessKey())
				.retrieve()
				.body(ImageSearchResponse.class);

			if (searchResponse == null || searchResponse.results() == null || searchResponse.results().isEmpty()) {
				log.debug("Unsplash에서 '{}' 검색 결과 없음", query);
				return null;
			}

			ImageSearchResponse.Photo photo = searchResponse.results().get(0);
			String imageUrl = photo.urls().regular();
			String authorName = photo.user() != null ? photo.user().name() : null;
			String authorUrl = photo.user() != null && photo.user().links() != null
				? photo.user().links().html()
				: null;

			return new ImageSearchResult(imageUrl, authorName, authorUrl, null);
		} catch (RestClientException e) {
			log.warn("Unsplash API 호출 실패 (query='{}'): {}", query, e.getMessage());
			return null;
		} catch (Exception e) {
			log.warn("Unsplash 응답 처리 실패 (query='{}'): {}", query, e.getMessage());
			return null;
		}
	}

	@Override
	public void recordDownload(String downloadUrl) {
		if (downloadUrl == null || downloadUrl.isBlank()) {
			return;
		}

		try {
			RestClient restClient = RestClient.builder().build();
			restClient.get()
				.uri(downloadUrl)
				.header("Authorization", "Client-ID " + properties.getAccessKey())
				.retrieve()
				.toBodilessEntity();
			log.debug("Unsplash 다운로드 기록 호출 성공");
		} catch (Exception e) {
			log.debug("Unsplash 다운로드 기록 호출 실패 (무시): {}", e.getMessage());
		}
	}
}
