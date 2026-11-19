package com.vium.recipe.service;

import com.vium.recipe.config.RecipeImageBackfillProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 레시피 이미지 다시 채우기의 정기 실행을 담당한다.
 * recipe.image-backfill.enabled=true일 때만 빈으로 등록된다.
 * Unsplash 데모 키 한도(시간당 50회)를 추천 요청과 나눠 쓰므로 batchSize를 작게 유지한다.
 * 레시피 1건당 검색 최대 2회다.
 */
@Component
@ConditionalOnProperty(name = "recipe.image-backfill.enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(RecipeImageBackfillProperties.class)
@RequiredArgsConstructor
@Slf4j
public class RecipeImageBackfillScheduler {

	private final RecipeImageBackfillService recipeImageBackfillService;
	private final RecipeImageBackfillProperties properties;

	@PostConstruct
	void logActivation() {
		log.info("레시피 이미지 다시 채우기 스케줄러 활성화: cron={}, zone={}, batchSize={}",
			properties.cron(), properties.timeZone(), properties.batchSize());
	}

	@Scheduled(cron = "${recipe.image-backfill.cron}", zone = "${recipe.image-backfill.time-zone}")
	public void run() {
		try {
			recipeImageBackfillService.backfillMissingImages(properties.batchSize());
		} catch (Exception e) {
			log.warn("레시피 이미지 다시 채우기 실패: {}", e.getMessage());
		}
	}
}
