package com.vium.recipe.service;

import com.vium.recipe.dto.ImageSearchResult;
import com.vium.recipe.entity.Recipe;
import com.vium.recipe.repository.RecipeRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * 이미지 검색이 실패해 image_url이 비어 있는 레시피를 최신순으로 batchSize개까지 다시 검색해 채운다.
 * 저장된 imageKeyword(LLM이 만든 영어 검색어)가 있으면 그것을, 없으면 레시피 제목을 검색어로 쓴다.
 * 외부 호출은 트랜잭션 밖에서 하고, 저장은 레시피마다 짧은 UPDATE 한 번으로 끝낸다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecipeImageBackfillService {

	private final RecipeRepository recipeRepository;
	private final RecipeImageFinder recipeImageFinder;

	public int backfillMissingImages(int batchSize) {
		List<Recipe> targets = recipeRepository.findByImageUrlIsNullOrderByIdDesc(PageRequest.of(0, batchSize));
		if (targets.isEmpty()) {
			return 0;
		}

		int filled = 0;
		for (Recipe recipe : targets) {
			String keyword = recipe.getImageKeyword() != null ? recipe.getImageKeyword() : recipe.getTitle();
			ImageSearchResult image = recipeImageFinder.find(keyword);
			if (image == null || image.imageUrl() == null) {
				continue;
			}
			filled += recipeRepository.fillMissingImage(
				recipe.getId(), image.imageUrl(), image.authorName(), image.authorUrl());
		}

		log.info("레시피 이미지 다시 채우기 완료: 대상 {}건, 채움 {}건, 실패 {}건",
			targets.size(), filled, targets.size() - filled);
		return filled;
	}
}
