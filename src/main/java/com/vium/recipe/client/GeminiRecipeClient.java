package com.vium.recipe.client;

import com.vium.recipe.config.LlmProperties;
import com.vium.recipe.dto.LlmRecipeDetailRequest;
import com.vium.recipe.dto.LlmRecipeDetailResponse;
import com.vium.recipe.dto.LlmRecipeRequest;
import com.vium.recipe.dto.LlmRecipeResponse;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

@Component
@Slf4j
public class GeminiRecipeClient implements LlmRecipeClient {

	private final LlmProperties properties;
	private final JsonMapper jsonMapper;
	private final RestClient restClient;
	private final RestClient detailRestClient;

	@Autowired
	public GeminiRecipeClient(LlmProperties properties, JsonMapper jsonMapper) {
		this(properties, jsonMapper,
			RestClient.builder().requestFactory(buildRequestFactory(properties.getTimeoutSeconds())).build(),
			RestClient.builder().requestFactory(buildRequestFactory(properties.getDetailTimeoutSeconds())).build());
	}

	GeminiRecipeClient(LlmProperties properties, JsonMapper jsonMapper, RestClient restClient, RestClient detailRestClient) {
		this.properties = properties;
		this.jsonMapper = jsonMapper;
		this.restClient = restClient;
		this.detailRestClient = detailRestClient;
	}

	private static JdkClientHttpRequestFactory buildRequestFactory(int timeoutSeconds) {
		Duration timeout = Duration.ofSeconds(timeoutSeconds);
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
			HttpClient.newBuilder().connectTimeout(timeout).build());
		factory.setReadTimeout(timeout);
		return factory;
	}

	private static final String GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent";
	private static final int MAX_OUTPUT_TOKENS = 8192;
	private static final String SYSTEM_INSTRUCTION =
		"당신은 자취생을 위한 레시피 추천 도우미입니다.\n반드시 지정된 JSON 형식으로만 답하세요.";

	@Override
	public LlmRecipeResponse generateRecipes(LlmRecipeRequest request) {
		long startedAt = System.currentTimeMillis();
		try {
			String userPrompt = buildUserPrompt(request);
			Map<String, Object> responseMap = callGemini(restClient, userPrompt, buildResponseSchema());
			LlmRecipeResponse response = parseGeminiResponse(responseMap);
			log.info("레시피 배치 생성 성공: batchId={}, elapsedMs={}, {}",
				request.batchId(), System.currentTimeMillis() - startedAt, formatUsage(responseMap));
			return response;
		} catch (GeminiCallException e) {
			log.warn("레시피 배치 생성 호출 실패: batchId={}, elapsedMs={}, {}",
				request.batchId(), System.currentTimeMillis() - startedAt, e.logDetail);
			throw e;
		}
	}

	@Override
	public LlmRecipeDetailResponse enrichRecipeDetail(LlmRecipeDetailRequest request) {
		long startedAt = System.currentTimeMillis();
		try {
			String userPrompt = buildDetailPrompt(request);
			Map<String, Object> responseMap = callGemini(detailRestClient, userPrompt, buildDetailResponseSchema());
			LlmRecipeDetailResponse response = parseDetailResponse(responseMap);
			log.info("레시피 상세 보강 성공: recipeId={}, elapsedMs={}, {}",
				request.recipeId(), System.currentTimeMillis() - startedAt, formatUsage(responseMap));
			return response;
		} catch (GeminiCallException e) {
			log.warn("레시피 상세 보강 호출 실패: recipeId={}, elapsedMs={}, {}",
				request.recipeId(), System.currentTimeMillis() - startedAt, e.logDetail);
			throw e;
		}
	}

	/**
	 * callGemini/parse 단계에서 발생한 실패를 호출부까지 그대로 전달해, 로그를 상위(generateRecipes/
	 * enrichRecipeDetail)에서 식별자(batchId/recipeId)·elapsedMs와 함께 한 줄로만 남기기 위한 내부 예외.
	 * message는 기존 공개 계약과 동일(LLM 호출 실패 / 응답 파싱 실패)하게 유지한다.
	 */
	private static final class GeminiCallException extends RuntimeException {
		private final String logDetail;

		GeminiCallException(String message, String logDetail) {
			super(message);
			this.logDetail = logDetail;
		}
	}

	/**
	 * HTTP 실패 원인을 대략적인 범주로 분류한다. 429는 RATE_LIMIT, 5xx는 UPSTREAM_ERROR, 그 외 4xx는
	 * CLIENT_ERROR, 응답 자체를 못 받았는데 원인이 타임아웃이면 TIMEOUT, 나머지는 CLIENT_ERROR로 묶는다.
	 */
	static String classifyFailureReason(RestClientException e, Integer status) {
		if (status != null) {
			if (status == 429) return "RATE_LIMIT";
			if (status / 100 == 5) return "UPSTREAM_ERROR";
			if (status / 100 == 4) return "CLIENT_ERROR";
			return "UNKNOWN";
		}
		for (Throwable cause = e; cause != null; cause = cause.getCause()) {
			if (cause instanceof java.net.http.HttpTimeoutException) {
				return "TIMEOUT";
			}
		}
		return "CLIENT_ERROR";
	}

	private String formatUsage(Map<String, Object> responseMap) {
		var usage = jsonMapper.valueToTree(responseMap).path("usageMetadata");
		if (usage.isMissingNode()) {
			return "tokens=N/A";
		}
		return "promptTokens=" + usage.path("promptTokenCount").asInt(-1)
			+ ", candidatesTokens=" + usage.path("candidatesTokenCount").asInt(-1)
			+ ", totalTokens=" + usage.path("totalTokenCount").asInt(-1);
	}

	private Map<String, Object> callGemini(RestClient client, String userPrompt, Map<String, Object> responseSchema) {
		try {
			Map<String, Object> requestBody = buildGeminiRequest(userPrompt, responseSchema);
			String url = GEMINI_API_URL.replace("{model}", properties.getModel());

			return client.post()
				.uri(url)
				.header("x-goog-api-key", properties.getApiKey())
				.header("Content-Type", "application/json")
				.body(requestBody)
				.retrieve()
				.body(new ParameterizedTypeReference<Map<String, Object>>() {});
		} catch (RestClientException e) {
			Integer status = e instanceof RestClientResponseException response ? response.getStatusCode().value() : null;
			String reason = classifyFailureReason(e, status);
			// HTTP 예외의 메시지와 cause에는 요청 URL이나 응답 원문이 포함될 수 있어 로그에 남기지 않는다.
			throw new GeminiCallException("LLM 호출 실패",
				"reason=" + reason + ", status=" + status + ", type=" + e.getClass().getSimpleName());
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

	private String buildDetailPrompt(LlmRecipeDetailRequest request) {
		StringBuilder sb = new StringBuilder("아래 레시피에 1인분 기준 재료 분량과 양념, 상세 조리 단계를 추가해 주세요.\n\n");
		sb.append("{\"title\": \"").append(escapeJson(request.title())).append("\", ")
			.append("\"category\": \"").append(request.category()).append("\", ")
			.append("\"ingredients\": [");
		for (int i = 0; i < request.ingredients().size(); i++) {
			if (i > 0) sb.append(", ");
			LlmRecipeDetailRequest.IngredientInfo ing = request.ingredients().get(i);
			sb.append("{\"catalogId\": ").append(ing.catalogId()).append(", ")
				.append("\"name\": \"").append(escapeJson(ing.name())).append("\"}");
		}
		sb.append("]}\n\n")
			.append("규칙:\n")
			.append("1. ingredients 목록의 catalogId를 그대로 전부 사용하세요. 추가하거나 빼지 마세요. ")
			.append("ingredients 목록에 있는 재료는 소금·간장 같은 양념이라도 ingredients에 분량(amount)을 쓰고, ")
			.append("seasonings에는 ingredients 목록에 없는 양념만 분량과 함께 쓰세요.\n")
			.append("2. 모든 분량(ingredients의 amount, seasonings의 amount)은 1인분 기준으로 계산하세요.\n")
			.append("3. 분량은 다음과 같은 형식 중 상황에 맞는 걸 골라 쓰세요: \"한 숟가락(15ml)\", \"두 숟가락\", \"10g\", ")
			.append("\"1/2개(약 80g)\", \"종이컵 1컵(180ml)\", \"한 꼬집\", \"약간\". ")
			.append("가능하면 숫자와 단위를 함께 쓰고, 정확히 재기 어려운 경우에만 \"한 꼬집\"이나 \"약간\"을 쓰세요.\n")
			.append("4. seasonings의 각 항목은 name과 amount(3번 형식)를 반드시 포함하고, 최대 10개까지만 쓰세요. ")
			.append("쓰지 않는 레시피면 빈 배열로 두세요.\n")
			.append("5. steps는 6~12단계로 작성하세요. 각 단계마다 다음을 포함해 요리 초보도 실패하지 않고 따라 할 수 있게 쓰세요: ")
			.append("불을 쓰는 단계는 불 세기(센 불/중간 불/약한 불 중 하나), 걸리는 시간(예: \"3분간\"), ")
			.append("다음 단계로 넘어가도 되는 상태 기준(예: \"가장자리가 노릇해지면\", \"물이 끓어오르면\"). ")
			.append("각 단계는 500자 이하로 쓰세요.");
		return sb.toString();
	}

	private String escapeJson(String str) {
		return str.replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
	}

	private Map<String, Object> buildGeminiRequest(String userPrompt, Map<String, Object> responseSchema) {
		return Map.of(
			"contents", List.of(
				Map.of("role", "user", "parts", List.of(Map.of("text", userPrompt)))
			),
			"system_instruction", Map.of("parts", List.of(Map.of("text", SYSTEM_INSTRUCTION))),
			"generationConfig", Map.of(
				"responseMimeType", "application/json",
				"responseSchema", responseSchema,
				"maxOutputTokens", MAX_OUTPUT_TOKENS,
				"thinkingConfig", Map.of("thinkingBudget", 0)
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

	private Map<String, Object> buildDetailResponseSchema() {
		return Map.of(
			"type", "OBJECT",
			"properties", Map.of(
				"ingredients", Map.of(
					"type", "ARRAY",
					"items", Map.of(
						"type", "OBJECT",
						"properties", Map.of(
							"catalogId", Map.of("type", "INTEGER"),
							"amount", Map.of("type", "STRING")
						),
						"required", List.of("catalogId", "amount")
					)
				),
				"seasonings", Map.of(
					"type", "ARRAY",
					"items", Map.of(
						"type", "OBJECT",
						"properties", Map.of(
							"name", Map.of("type", "STRING"),
							"amount", Map.of("type", "STRING")
						),
						"required", List.of("name", "amount")
					)
				),
				"steps", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"))
			),
			"required", List.of("ingredients", "seasonings", "steps")
		);
	}

	LlmRecipeResponse parseGeminiResponse(Map<String, Object> responseMap) {
		String stage = "envelope";
		String finishReason = "MISSING";
		StringBuilder text = new StringBuilder();
		try {
			var candidates = jsonMapper.valueToTree(responseMap).path("candidates");
			if (!candidates.isArray() || candidates.isEmpty()) {
				throw new IllegalArgumentException("Gemini 응답에 candidates가 없습니다");
			}
			var candidate = candidates.get(0);
			String rawFinishReason = candidate.path("finishReason").asText();
			if (candidate.has("finishReason")) {
				finishReason = List.of("STOP", "MAX_TOKENS", "SAFETY", "RECITATION", "OTHER")
					.contains(rawFinishReason) ? rawFinishReason : "OTHER";
			}
			if (candidate.has("finishReason") && !"STOP".equals(candidate.path("finishReason").asText())) {
				throw new IllegalArgumentException("Gemini 응답이 정상적으로 완료되지 않았습니다");
			}
			var parts = candidate.path("content").path("parts");
			if (!parts.isArray()) {
				throw new IllegalArgumentException("Gemini 응답에 parts가 없습니다");
			}
			for (var part : parts) {
				if (!part.path("thought").asBoolean(false) && part.path("text").isString()) {
					text.append(part.path("text").asText());
				}
			}
			stage = "json";
			var root = jsonMapper.readTree(text.toString());
			if (root == null || !root.path("recipes").isArray()) {
				throw new IllegalArgumentException("Gemini 응답에 recipes 배열이 없습니다");
			}
			stage = "mapping";
			return jsonMapper.treeToValue(root, LlmRecipeResponse.class);
		} catch (Exception e) {
			throw new GeminiCallException("응답 파싱 실패",
				"stage=" + stage + ", finishReason=" + finishReason + ", textLength=" + text.length()
					+ ", type=" + e.getClass().getSimpleName());
		}
	}

	LlmRecipeDetailResponse parseDetailResponse(Map<String, Object> responseMap) {
		String stage = "envelope";
		String finishReason = "MISSING";
		StringBuilder text = new StringBuilder();
		try {
			var candidates = jsonMapper.valueToTree(responseMap).path("candidates");
			if (!candidates.isArray() || candidates.isEmpty()) {
				throw new IllegalArgumentException("Gemini 응답에 candidates가 없습니다");
			}
			var candidate = candidates.get(0);
			String rawFinishReason = candidate.path("finishReason").asText();
			if (candidate.has("finishReason")) {
				finishReason = List.of("STOP", "MAX_TOKENS", "SAFETY", "RECITATION", "OTHER")
					.contains(rawFinishReason) ? rawFinishReason : "OTHER";
			}
			if (candidate.has("finishReason") && !"STOP".equals(candidate.path("finishReason").asText())) {
				throw new IllegalArgumentException("Gemini 응답이 정상적으로 완료되지 않았습니다");
			}
			var parts = candidate.path("content").path("parts");
			if (!parts.isArray()) {
				throw new IllegalArgumentException("Gemini 응답에 parts가 없습니다");
			}
			for (var part : parts) {
				if (!part.path("thought").asBoolean(false) && part.path("text").isString()) {
					text.append(part.path("text").asText());
				}
			}
			stage = "json";
			var root = jsonMapper.readTree(text.toString());
			if (root == null || !root.path("ingredients").isArray() || !root.path("seasonings").isArray()
				|| !root.path("steps").isArray()) {
				throw new IllegalArgumentException("Gemini 응답에 ingredients/seasonings/steps 배열이 없습니다");
			}
			stage = "mapping";
			return jsonMapper.treeToValue(root, LlmRecipeDetailResponse.class);
		} catch (Exception e) {
			throw new GeminiCallException("응답 파싱 실패",
				"stage=" + stage + ", finishReason=" + finishReason + ", textLength=" + text.length()
					+ ", type=" + e.getClass().getSimpleName());
		}
	}
}
