package com.vium.report.client;

import com.vium.recipe.config.LlmProperties;
import com.vium.report.dto.InsightDto;
import com.vium.report.dto.LlmReportResponse;
import com.vium.report.dto.MonthlyWasteStats;
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
public class GeminiReportClient implements LlmReportClient {

	private static final String GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent";
	private static final int MAX_OUTPUT_TOKENS = 4096;
	private static final int MAX_INSIGHTS = 5;
	private static final int MAX_SUGGESTIONS = 5;
	private static final int MAX_INSIGHT_TITLE_LENGTH = 60;
	private static final int MAX_INSIGHT_BODY_LENGTH = 300;
	private static final int MAX_SUGGESTION_LENGTH = 200;
	private static final String SYSTEM_INSTRUCTION = """
		당신은 자취생의 식재료 낭비를 줄이도록 돕는 도우미입니다. 사용자를 탓하거나 나무라는 톤을 쓰지 말고,
		다음 장보기에서 무엇을 어떻게 바꾸면 좋을지 돕는 따뜻한 톤으로 한국어로만 답하세요.
		반드시 지정된 JSON 형식으로만 답하세요.""";

	private final LlmProperties properties;
	private final JsonMapper jsonMapper;
	private final RestClient restClient;

	@Autowired
	public GeminiReportClient(LlmProperties properties, JsonMapper jsonMapper) {
		this(properties, jsonMapper,
			RestClient.builder().requestFactory(buildRequestFactory(properties.getTimeoutSeconds())).build());
	}

	GeminiReportClient(LlmProperties properties, JsonMapper jsonMapper, RestClient restClient) {
		this.properties = properties;
		this.jsonMapper = jsonMapper;
		this.restClient = restClient;
	}

	private static JdkClientHttpRequestFactory buildRequestFactory(int timeoutSeconds) {
		Duration timeout = Duration.ofSeconds(timeoutSeconds);
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
			HttpClient.newBuilder().connectTimeout(timeout).build());
		factory.setReadTimeout(timeout);
		return factory;
	}

	@Override
	public LlmReportResponse generateReport(MonthlyWasteStats stats) {
		long startedAt = System.currentTimeMillis();
		try {
			String userPrompt = buildUserPrompt(stats);
			Map<String, Object> responseMap = callGemini(userPrompt);
			LlmReportResponse response = parseGeminiResponse(responseMap);
			log.info("AI 낭비 리포트 생성 성공: elapsedMs={}", System.currentTimeMillis() - startedAt);
			return response;
		} catch (GeminiReportCallException e) {
			log.warn("AI 낭비 리포트 생성 실패: elapsedMs={}, {}", System.currentTimeMillis() - startedAt, e.logDetail);
			throw e;
		}
	}

	private static final class GeminiReportCallException extends RuntimeException {
		private final String logDetail;

		GeminiReportCallException(String message, String logDetail) {
			super(message);
			this.logDetail = logDetail;
		}
	}

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

	private Map<String, Object> callGemini(String userPrompt) {
		try {
			Map<String, Object> requestBody = buildGeminiRequest(userPrompt);
			String url = GEMINI_API_URL.replace("{model}", properties.getModel());

			return restClient.post()
				.uri(url)
				.header("x-goog-api-key", properties.getApiKey())
				.header("Content-Type", "application/json")
				.body(requestBody)
				.retrieve()
				.body(new ParameterizedTypeReference<Map<String, Object>>() {});
		} catch (RestClientException e) {
			Integer status = e instanceof RestClientResponseException response ? response.getStatusCode().value() : null;
			String reason = classifyFailureReason(e, status);
			throw new GeminiReportCallException("LLM 호출 실패",
				"reason=" + reason + ", status=" + status + ", type=" + e.getClass().getSimpleName());
		}
	}

	private String buildUserPrompt(MonthlyWasteStats stats) {
		StringBuilder sb = new StringBuilder("아래 한 달치 소진·폐기 집계 통계를 보고 낭비 리포트를 만들어 주세요.\n\n");
		sb.append("{\"consumedCount\": ").append(stats.consumedCount())
			.append(", \"disposedCount\": ").append(stats.disposedCount())
			.append(", \"consumedAmount\": ").append(stats.consumedAmount())
			.append(", \"disposedAmount\": ").append(stats.disposedAmount())
			.append(", \"avgDaysPurchaseToDispose\": ")
			.append(stats.avgDaysPurchaseToDispose() == null ? "null" : stats.avgDaysPurchaseToDispose())
			.append(", \"categoryWaste\": [");
		for (int i = 0; i < stats.categoryWaste().size(); i++) {
			if (i > 0) sb.append(", ");
			var c = stats.categoryWaste().get(i);
			sb.append("{\"category\": \"").append(escapeJson(c.categoryName())).append("\", ")
				.append("\"wasteCount\": ").append(c.wasteCount()).append(", ")
				.append("\"wasteAmount\": ").append(c.wasteAmount()).append("}");
		}
		sb.append("], \"itemWaste\": [");
		for (int i = 0; i < stats.itemWaste().size(); i++) {
			if (i > 0) sb.append(", ");
			var it = stats.itemWaste().get(i);
			sb.append("{\"item\": \"").append(escapeJson(it.itemName())).append("\", ")
				.append("\"wasteCount\": ").append(it.wasteCount()).append(", ")
				.append("\"wasteAmount\": ").append(it.wasteAmount()).append("}");
		}
		sb.append("]}\n\n")
			.append("- consumedCount/disposedCount: 이번 달 다 쓴 횟수/버린 횟수\n")
			.append("- consumedAmount/disposedAmount: 다 쓴 금액/버린 금액(원)\n")
			.append("- avgDaysPurchaseToDispose: 구매일부터 폐기까지 평균 일수(null이면 데이터 없음)\n")
			.append("- categoryWaste/itemWaste: 카테고리별/재료별 폐기 횟수·금액(금액 큰 순)\n\n")
			.append("규칙:\n")
			.append("1. summary는 이번 달 낭비 특징을 한두 문장으로 요약하세요.\n")
			.append("2. insights는 1~5개, 각 title은 60자 이하, body는 300자 이하로 작성하세요.\n")
			.append("3. suggestions는 1~5개, 각 200자 이하이며 다음 장보기에서 바로 실천할 수 있는 제안을 쓰세요.\n")
			.append("4. 사용자를 탓하지 말고, 통계에 없는 사실은 지어내지 마세요.");
		return sb.toString();
	}

	private String escapeJson(String str) {
		return str.replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
	}

	private Map<String, Object> buildGeminiRequest(String userPrompt) {
		return Map.of(
			"contents", List.of(
				Map.of("role", "user", "parts", List.of(Map.of("text", userPrompt)))
			),
			"system_instruction", Map.of("parts", List.of(Map.of("text", SYSTEM_INSTRUCTION))),
			"generationConfig", Map.of(
				"responseMimeType", "application/json",
				"responseSchema", buildResponseSchema(),
				"maxOutputTokens", MAX_OUTPUT_TOKENS,
				"thinkingConfig", Map.of("thinkingBudget", 0)
			)
		);
	}

	private Map<String, Object> buildResponseSchema() {
		return Map.of(
			"type", "OBJECT",
			"properties", Map.of(
				"summary", Map.of("type", "STRING"),
				"insights", Map.of(
					"type", "ARRAY",
					"items", Map.of(
						"type", "OBJECT",
						"properties", Map.of(
							"title", Map.of("type", "STRING"),
							"body", Map.of("type", "STRING")
						),
						"required", List.of("title", "body")
					)
				),
				"suggestions", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"))
			),
			"required", List.of("summary", "insights", "suggestions")
		);
	}

	LlmReportResponse parseGeminiResponse(Map<String, Object> responseMap) {
		String stage = "envelope";
		String finishReason = "MISSING";
		StringBuilder text = new StringBuilder();
		try {
			var candidates = jsonMapper.valueToTree(responseMap).path("candidates");
			if (!candidates.isArray() || candidates.isEmpty()) {
				throw new IllegalArgumentException("Gemini 응답에 candidates가 없습니다");
			}
			var candidate = candidates.get(0);
			if (candidate.has("finishReason")) {
				finishReason = candidate.path("finishReason").asText();
				if (!"STOP".equals(finishReason)) {
					throw new IllegalArgumentException("Gemini 응답이 정상적으로 완료되지 않았습니다");
				}
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
			if (root == null || !root.path("summary").isString() || !root.path("insights").isArray()
				|| !root.path("suggestions").isArray()) {
				throw new IllegalArgumentException("Gemini 응답에 summary/insights/suggestions가 없습니다");
			}
			stage = "mapping";
			LlmReportResponse response = jsonMapper.treeToValue(root, LlmReportResponse.class);
			stage = "validation";
			validate(response);
			return response;
		} catch (Exception e) {
			throw new GeminiReportCallException("응답 파싱 실패",
				"stage=" + stage + ", finishReason=" + finishReason + ", textLength=" + text.length()
					+ ", type=" + e.getClass().getSimpleName());
		}
	}

	private void validate(LlmReportResponse response) {
		if (response.summary() == null || response.summary().isBlank()) {
			throw new IllegalArgumentException("summary가 비어 있습니다");
		}
		List<InsightDto> insights = response.insights();
		if (insights == null || insights.isEmpty() || insights.size() > MAX_INSIGHTS) {
			throw new IllegalArgumentException("insights 개수가 올바르지 않습니다");
		}
		for (InsightDto insight : insights) {
			if (insight.title() == null || insight.title().isBlank() || insight.title().length() > MAX_INSIGHT_TITLE_LENGTH) {
				throw new IllegalArgumentException("insight title이 올바르지 않습니다");
			}
			if (insight.body() == null || insight.body().isBlank() || insight.body().length() > MAX_INSIGHT_BODY_LENGTH) {
				throw new IllegalArgumentException("insight body가 올바르지 않습니다");
			}
		}
		List<String> suggestions = response.suggestions();
		if (suggestions == null || suggestions.isEmpty() || suggestions.size() > MAX_SUGGESTIONS) {
			throw new IllegalArgumentException("suggestions 개수가 올바르지 않습니다");
		}
		for (String suggestion : suggestions) {
			if (suggestion == null || suggestion.isBlank() || suggestion.length() > MAX_SUGGESTION_LENGTH) {
				throw new IllegalArgumentException("suggestion이 올바르지 않습니다");
			}
		}
	}
}
