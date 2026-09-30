package com.vium.recipe.client;

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
}
