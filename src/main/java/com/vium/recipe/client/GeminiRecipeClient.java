package com.vium.recipe.client;

import com.vium.recipe.config.LlmProperties;
import com.vium.recipe.dto.LlmRecipeRequest;
import com.vium.recipe.dto.LlmRecipeResponse;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@RequiredArgsConstructor
@Slf4j
public class GeminiRecipeClient implements LlmRecipeClient {

	private final LlmProperties properties;

	private static final String GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent";
	private static final int MAX_OUTPUT_TOKENS = 8192;

	@Override
	public LlmRecipeResponse generateRecipes(LlmRecipeRequest request) {
		try {
			String systemInstruction = "당신은 자취생을 위한 레시피 추천 도우미입니다.\n반드시 지정된 JSON 형식으로만 답하세요.";
			String userPrompt = buildUserPrompt(request);

			Map<String, Object> requestBody = buildGeminiRequest(systemInstruction, userPrompt);

			String url = GEMINI_API_URL.replace("{model}", properties.getModel());

			RestClient restClient = RestClient.builder().build();

			Map<String, Object> responseMap = restClient.post()
				.uri(url + "?key=" + properties.getApiKey())
				.header("Content-Type", "application/json")
				.body(requestBody)
				.retrieve()
				.body(new ParameterizedTypeReference<Map<String, Object>>() {});

			return parseGeminiResponse(responseMap);
		} catch (RestClientException e) {
			log.warn("Gemini API 호출 실패 (재시도 대상): {}", e.getMessage());
			throw new RuntimeException("LLM 호출 실패: " + e.getMessage(), e);
		} catch (Exception e) {
			log.error("Gemini 응답 처리 실패", e);
			throw new RuntimeException("LLM 응답 처리 실패: " + e.getMessage(), e);
		}
	}

	private String buildUserPrompt(LlmRecipeRequest request) {
		StringBuilder sb = new StringBuilder("아래 입력을 보고 레시피를 만들어 주세요.\n\n");
		sb.append("{\"ingredients\": [");
		for (int i = 0; i < request.ingredients().size(); i++) {
			if (i > 0) sb.append(", ");
			LlmRecipeRequest.IngredientInfo ing = request.ingredients().get(i);
			sb.append("{\"catalogId\": ").append(ing.catalogId()).append(", ")
				.append("\"name\": \"").append(escapeJson(ing.name())).append("\", ")
				.append("\"expireInDays\": ").append(ing.expireInDays()).append("}");
		}
		sb.append("], \"need\": {");
		boolean first = true;
		for (Map.Entry<String, Integer> entry : request.need().entrySet()) {
			if (!first) sb.append(", ");
			sb.append("\"").append(entry.getKey()).append("\": ").append(entry.getValue());
			first = false;
		}
		sb.append("}, \"excludeTitles\": [");
		for (int i = 0; i < request.excludeTitles().size(); i++) {
			if (i > 0) sb.append(", ");
			sb.append("\"").append(escapeJson(request.excludeTitles().get(i))).append("\"");
		}
		sb.append("]}\n\n")
			.append("- ingredients: 사용자가 가진 재료. expireInDays는 소비기한까지 남은 일수(null은 소비기한 없음)\n")
			.append("- need: 카테고리별로 만들 레시피 개수\n")
			.append("- excludeTitles: 이미 추천한 레시피. 같거나 비슷한 레시피는 만들지 마세요.\n\n")
			.append("규칙:\n")
			.append("1. need에 있는 카테고리만, 카테고리마다 최대 need개를 만드세요.\n")
			.append("2. 재료는 ingredients의 catalogId만 사용하세요. 목록에 없는 재료는 쓰지 마세요.\n")
			.append("3. expireInDays가 작은 재료를 우선 사용하세요.\n")
			.append("4. cookTime은 1~180 사이 정수(분)입니다.\n")
			.append("5. steps는 2~6단계로, 요리 초보도 따라 할 수 있게 한국어로 짧게 쓰세요.\n")
			.append("6. imageKeyword는 음식 사진 검색용 영어 음식 이름입니다.\n")
			.append("7. reason은 이 레시피를 추천하는 이유를 한국어 한 문장으로 쓰세요.");
		return sb.toString();
	}

	private String escapeJson(String str) {
		return str.replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
	}

	private Map<String, Object> buildGeminiRequest(String systemInstruction, String userPrompt) {
		return Map.of(
			"contents", List.of(
				Map.of("role", "user", "parts", List.of(Map.of("text", userPrompt)))
			),
			"system_instruction", Map.of("parts", List.of(Map.of("text", systemInstruction))),
			"generationConfig", Map.of(
				"responseMimeType", "application/json",
				"responseSchema", buildResponseSchema(),
				"maxOutputTokens", MAX_OUTPUT_TOKENS
			)
		);
	}

	private Map<String, Object> buildResponseSchema() {
		return Map.of(
			"type", "OBJECT",
			"properties", Map.of(
				"recipes", Map.of(
					"type", "ARRAY",
					"items", Map.of(
						"type", "OBJECT",
						"properties", Map.of(
							"title", Map.of("type", "STRING"),
							"category", Map.of("type", "STRING",
								"enum", List.of("KOREAN", "CHINESE", "WESTERN", "JAPANESE", "DESSERT")),
							"cookTime", Map.of("type", "INTEGER"),
							"ingredientCatalogIds", Map.of("type", "ARRAY",
								"items", Map.of("type", "INTEGER")),
							"steps", Map.of("type", "ARRAY", "items", Map.of("type", "STRING")),
							"imageKeyword", Map.of("type", "STRING"),
							"reason", Map.of("type", "STRING")
						),
						"required", List.of("title", "category", "cookTime", "ingredientCatalogIds",
							"steps", "imageKeyword")
					)
				)
			),
			"required", List.of("recipes")
		);
	}

	@SuppressWarnings("unchecked")
	private LlmRecipeResponse parseGeminiResponse(Map<String, Object> responseMap) {
		try {
			List<Map<String, Object>> candidates = (List<Map<String, Object>>) responseMap.get("candidates");
			if (candidates == null || candidates.isEmpty()) {
				throw new RuntimeException("Gemini 응답에 candidates가 없습니다");
			}

			Map<String, Object> candidate = candidates.get(0);
			Map<String, Object> content = (Map<String, Object>) candidate.get("content");
			List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
			String jsonText = (String) parts.get(0).get("text");

			// 간단한 JSON 파싱 (RestClient의 자동 역직렬화 사용)
			// 실제 구현에서는 Record의 fromJson 또는 다른 방식 사용
			log.debug("LLM 응답 JSON: {}", jsonText);
			// TODO: jsonText를 LlmRecipeResponse로 변환
			// 여기서는 임시로 빈 응답 반환
			return new LlmRecipeResponse(List.of());
		} catch (Exception e) {
			log.error("Gemini 응답 파싱 실패", e);
			throw new RuntimeException("응답 파싱 실패", e);
		}
	}
}
