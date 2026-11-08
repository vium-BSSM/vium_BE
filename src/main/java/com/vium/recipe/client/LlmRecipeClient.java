package com.vium.recipe.client;

import com.vium.recipe.dto.LlmRecipeDetailRequest;
import com.vium.recipe.dto.LlmRecipeDetailResponse;
import com.vium.recipe.dto.LlmRecipeRequest;
import com.vium.recipe.dto.LlmRecipeResponse;

public interface LlmRecipeClient {

	/**
	 * LLM을 호출하여 레시피를 생성합니다.
	 *
	 * @param request 사용자 재료, 필요한 레시피 개수, 제외 제목
	 * @return LLM이 생성한 레시피 목록
	 * @throws RuntimeException 타임아웃, 네트워크 오류, 파싱 실패 등
	 */
	LlmRecipeResponse generateRecipes(LlmRecipeRequest request);

	/**
	 * LLM을 호출하여 기존 레시피에 재료 분량과 상세 조리 단계(6~12)를 보강합니다.
	 * 재료 목록은 추가/삭제되지 않고 그대로 유지됩니다.
	 *
	 * @param request 레시피 제목·카테고리와 기존 재료 목록
	 * @return 재료별 분량과 상세 조리 단계
	 * @throws RuntimeException 타임아웃, 네트워크 오류, 파싱 실패 등
	 */
	LlmRecipeDetailResponse enrichRecipeDetail(LlmRecipeDetailRequest request);
}
