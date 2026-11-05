package com.vium.recipe.service;

import com.vium.ingredient.service.IngredientCatalogService;
import com.vium.recipe.client.LlmRecipeClient;
import com.vium.recipe.config.LlmProperties;
import com.vium.recipe.dto.LlmRecipeDetailRequest;
import com.vium.recipe.dto.LlmRecipeDetailResponse;
import com.vium.recipe.entity.Recipe;
import com.vium.recipe.entity.RecipeIngredient;
import com.vium.recipe.repository.RecipeIngredientRepository;
import com.vium.recipe.repository.RecipeRepository;
import com.vium.recipe.util.RecipeDetailValidator;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 레시피 상세(재료 분량 + 6~12단계)를 상세 조회 시점에 레시피 1건씩 지연 생성한다.
 * 동시에 여러 요청이 같은 레시피를 열람해도 1건만 실제로 생성하도록
 * {@link RecipeRepository#claimDetailGeneration}로 원자적으로 선점한 뒤 처리한다.
 * LLM 호출(느린 네트워크 작업)은 트랜잭션 밖에서 수행하고, 저장은 {@link RecipeDetailPersistenceService}에 위임한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecipeDetailEnrichmentService {

	/** GENERATING 선점이 멈춰 보일 때 재시도를 허용하기까지의 여유 시간. */
	private static final Duration STALE_MARGIN = Duration.ofSeconds(10);

	/** staleAfter()의 최소값. 상세 타임아웃을 짧게 줄여도 재시도 간격이 너무 짧아지지 않도록 하는 하한선. */
	private static final Duration STALE_FLOOR = Duration.ofMinutes(2);

	/** BASIC 상태에서 직전 실패 후 재시도를 허용하기까지의 쿨다운. GENERATING stale 기준과는 독립적인 정책값이다. */
	private static final Duration RETRY_COOLDOWN = Duration.ofSeconds(30);

	private final RecipeRepository recipeRepository;
	private final RecipeIngredientRepository recipeIngredientRepository;
	private final IngredientCatalogService ingredientCatalogService;
	private final LlmRecipeClient llmRecipeClient;
	private final RecipeDetailValidator recipeDetailValidator;
	private final RecipeDetailPersistenceService persistenceService;
	private final LlmProperties llmProperties;

	/**
	 * 레시피가 BASIC 상태면 선점을 시도하고, 선점에 성공한 요청만 실제로 LLM을 호출해 보강한다.
	 * 이미 다른 요청이 처리 중이거나 직전에 실패해 쿨다운 중이면 아무 일도 하지 않고 즉시 반환한다.
	 * 호출자는 이 요청에서 실제로 상세가 갱신됐을 때만(true) 응답을 다시 조회하면 된다.
	 *
	 * @return 이번 호출로 상세 보강이 성공했으면 true, 선점 실패·이미 상세·LLM 실패 등은 false
	 */
	public boolean enrichIfNeeded(Long recipeId) {
		Instant now = Instant.now();
		Instant retryCooldownBefore = now.minus(RETRY_COOLDOWN);
		Instant staleBefore = now.minus(staleAfter());
		if (recipeRepository.claimDetailGeneration(recipeId, now, retryCooldownBefore, staleBefore) == 0) {
			return false;
		}

		try {
			Recipe recipe = recipeRepository.findById(recipeId)
				.orElseThrow(() -> new IllegalStateException("보강 대상 레시피를 찾을 수 없습니다: " + recipeId));
			List<RecipeIngredient> ingredients = recipeIngredientRepository.findByRecipeIdOrderByIdAsc(recipeId);

			LlmRecipeDetailResponse response = llmRecipeClient.enrichRecipeDetail(buildRequest(recipe, ingredients));

			Set<Long> expectedCatalogIds = ingredients.stream()
				.map(RecipeIngredient::getIngredientCatalogId)
				.collect(Collectors.toSet());
			Set<String> existingIngredientNames = ingredients.stream()
				.map(ri -> nameOrFallback(ri.getIngredientCatalogId()))
				.collect(Collectors.toSet());
			LlmRecipeDetailResponse validated = recipeDetailValidator.validate(response, expectedCatalogIds,
				existingIngredientNames);

			persistenceService.applyEnrichment(recipeId, validated);
			return true;
		} catch (Exception e) {
			log.warn("레시피 상세 보강 실패, 다음 열람 때 재시도: recipeId={}, {}", recipeId, e.getMessage());
			persistenceService.resetToBasic(recipeId);
			return false;
		}
	}

	private LlmRecipeDetailRequest buildRequest(Recipe recipe, List<RecipeIngredient> ingredients) {
		List<LlmRecipeDetailRequest.IngredientInfo> infos = ingredients.stream()
			.map(ri -> new LlmRecipeDetailRequest.IngredientInfo(ri.getIngredientCatalogId(),
				nameOrFallback(ri.getIngredientCatalogId())))
			.toList();
		return new LlmRecipeDetailRequest(recipe.getId(), recipe.getTitle(), recipe.getCategory().name(), infos);
	}

	private String nameOrFallback(Long catalogId) {
		String name = ingredientCatalogService.getNameById(catalogId);
		return name != null ? name : "재료";
	}

	/**
	 * GENERATING 선점을 재시도 가능하다고 볼 때까지의 기준 시간.
	 * 상세 보강 전용 connect+read 타임아웃을 모두 소진한 최악의 경우(최대 detailTimeoutSeconds*2)보다
	 * 여유를 두어, 아직 응답을 기다리는 중인 요청을 중복 선점하지 않도록 한다.
	 * 타임아웃을 짧게 줄여도 STALE_FLOOR(2분) 밑으로는 내려가지 않는다.
	 */
	Duration staleAfter() {
		Duration computed = Duration.ofSeconds(llmProperties.getDetailTimeoutSeconds()).multipliedBy(2).plus(STALE_MARGIN);
		return computed.compareTo(STALE_FLOOR) > 0 ? computed : STALE_FLOOR;
	}
}
