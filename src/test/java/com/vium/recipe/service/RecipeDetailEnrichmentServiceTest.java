package com.vium.recipe.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.vium.ingredient.service.IngredientCatalogService;
import com.vium.recipe.client.LlmRecipeClient;
import com.vium.recipe.config.LlmProperties;
import com.vium.recipe.repository.RecipeIngredientRepository;
import com.vium.recipe.repository.RecipeRepository;
import com.vium.recipe.util.RecipeDetailValidator;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RecipeDetailEnrichmentServiceTest {

	private RecipeDetailEnrichmentService service(int detailTimeoutSeconds) {
		LlmProperties properties = new LlmProperties();
		properties.setDetailTimeoutSeconds(detailTimeoutSeconds);
		return new RecipeDetailEnrichmentService(
			mock(RecipeRepository.class),
			mock(RecipeIngredientRepository.class),
			mock(IngredientCatalogService.class),
			mock(LlmRecipeClient.class),
			mock(RecipeDetailValidator.class),
			mock(RecipeDetailPersistenceService.class),
			properties);
	}

	@Test
	void staleAfterExceedsWorstCaseLlmCallDuration() {
		// connect timeout + read timeout이 각각 detailTimeoutSeconds이므로 한 번의 호출이 최악의 경우
		// detailTimeoutSeconds*2까지 걸릴 수 있다. staleAfter는 이보다 길어야 진행 중인 요청을 중복 선점하지 않는다.
		Duration staleAfter = service(90).staleAfter();
		assertThat(staleAfter).isGreaterThan(Duration.ofSeconds(90).multipliedBy(2));
	}

	@Test
	void staleAfterScalesWithConfiguredTimeoutAboveFloor() {
		// 90초, 120초 둘 다 2분 하한선보다 큰 값을 만들어내므로 설정값 차이가 그대로 반영된다.
		Duration shorterTimeout = service(90).staleAfter();
		Duration longerTimeout = service(120).staleAfter();
		assertThat(longerTimeout).isGreaterThan(shorterTimeout);
	}

	@Test
	void staleAfterNeverGoesBelowTwoMinuteFloor() {
		// detail-timeout-seconds를 20~30초로 짧게 줄여도 재시도 간격이 너무 짧아지지 않도록 2분 밑으로는 안 내려간다.
		Duration staleAfter = service(25).staleAfter();
		assertThat(staleAfter).isGreaterThanOrEqualTo(Duration.ofMinutes(2));
	}
}
