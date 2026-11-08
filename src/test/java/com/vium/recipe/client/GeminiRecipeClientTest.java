package com.vium.recipe.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vium.recipe.config.LlmProperties;
import java.io.IOException;
import java.net.http.HttpConnectTimeoutException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

class GeminiRecipeClientTest {
	private final GeminiRecipeClient client = new GeminiRecipeClient(new LlmProperties(), JsonMapper.builder().build());
	private static final String JSON = """
		{"recipes":[{"title":"Soup","category":"KOREAN","cookTime":10,
		"ingredientCatalogIds":[1,2],"steps":["Prepare","Cook"],"imageKeyword":"soup","reason":"Use vegetables"}]}
		""";

	@Test
	void parsesRecipeFieldsFromGeminiEnvelope() {
		var result = client.parseGeminiResponse(envelope(List.of(Map.of("text", JSON)), "STOP"));
		assertThat(result.recipes()).singleElement().satisfies(recipe -> {
			assertThat(recipe.title()).isEqualTo("Soup");
			assertThat(recipe.category()).isEqualTo("KOREAN");
			assertThat(recipe.cookTime()).isEqualTo(10);
			assertThat(recipe.ingredientCatalogIds()).containsExactly(1, 2);
			assertThat(recipe.steps()).containsExactly("Prepare", "Cook");
			assertThat(recipe.imageKeyword()).isEqualTo("soup");
			assertThat(recipe.reason()).isEqualTo("Use vegetables");
		});
	}

	@Test
	void joinsTextPartsAndSkipsThoughts() {
		var result = client.parseGeminiResponse(envelope(List.of(
			Map.of("text", "analysis", "thought", true),
			Map.of("text", JSON.substring(0, 30)), Map.of("text", JSON.substring(30))), "STOP"));
		assertThat(result.recipes()).hasSize(1);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "{", "null", "{}", "{\"recipes\":null}", "{\"recipes\":{}}"})
	void rejectsMalformedOrMissingRecipeArray(String json) {
		assertThatThrownBy(() -> client.parseGeminiResponse(envelope(List.of(Map.of("text", json)), "STOP")))
			.isInstanceOf(RuntimeException.class).hasMessage("응답 파싱 실패");
	}

	@Test
	void rejectsMissingCandidatesAndBlockedOrTruncatedResponses() {
		assertThatThrownBy(() -> client.parseGeminiResponse(Map.of())).isInstanceOf(RuntimeException.class);
		assertThatThrownBy(() -> client.parseGeminiResponse(null)).isInstanceOf(RuntimeException.class);
		assertThatThrownBy(() -> client.parseGeminiResponse(envelope(List.of(Map.of("text", JSON)), "MAX_TOKENS")))
			.isInstanceOf(RuntimeException.class);
		assertThatThrownBy(() -> client.parseGeminiResponse(envelope(List.of(), "SAFETY")))
			.isInstanceOf(RuntimeException.class);
	}

	private static final String DETAIL_JSON = """
		{"ingredients":[{"catalogId":1,"amount":"1개"}],
		"seasonings":[{"name":"소금","amount":"한 꼬집"}],
		"steps":["물을 끓인다","계란을 넣는다"]}
		""";

	@Test
	void parsesDetailFieldsIncludingSeasonings() {
		var result = client.parseDetailResponse(envelope(List.of(Map.of("text", DETAIL_JSON)), "STOP"));
		assertThat(result.ingredients()).singleElement().satisfies(ingredient -> {
			assertThat(ingredient.catalogId()).isEqualTo(1L);
			assertThat(ingredient.amount()).isEqualTo("1개");
		});
		assertThat(result.seasonings()).singleElement().satisfies(seasoning -> {
			assertThat(seasoning.name()).isEqualTo("소금");
			assertThat(seasoning.amount()).isEqualTo("한 꼬집");
		});
		assertThat(result.steps()).containsExactly("물을 끓인다", "계란을 넣는다");
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "{", "null", "{}", "{\"ingredients\":[],\"steps\":[]}"})
	void rejectsMalformedOrMissingDetailFields(String json) {
		assertThatThrownBy(() -> client.parseDetailResponse(envelope(List.of(Map.of("text", json)), "STOP")))
			.isInstanceOf(RuntimeException.class).hasMessage("응답 파싱 실패");
	}

	@Test
	void classifiesRateLimitStatus() {
		var e = new RestClientResponseException("429", 429, "Too Many Requests", null, null, null);
		assertThat(GeminiRecipeClient.classifyFailureReason(e, 429)).isEqualTo("RATE_LIMIT");
	}

	@Test
	void classifiesServerErrorStatusAsUpstreamError() {
		var e = new RestClientResponseException("503", 503, "Service Unavailable", null, null, null);
		assertThat(GeminiRecipeClient.classifyFailureReason(e, 503)).isEqualTo("UPSTREAM_ERROR");
	}

	@Test
	void classifiesOtherClientErrorStatus() {
		var e = new RestClientResponseException("404", 404, "Not Found", null, null, null);
		assertThat(GeminiRecipeClient.classifyFailureReason(e, 404)).isEqualTo("CLIENT_ERROR");
	}

	@Test
	void classifiesUnexpectedStatusAsUnknown() {
		var e = new RestClientResponseException("301", 301, "Moved", null, null, null);
		assertThat(GeminiRecipeClient.classifyFailureReason(e, 301)).isEqualTo("UNKNOWN");
	}

	@Test
	void classifiesTimeoutCauseAsTimeoutWhenNoStatus() {
		var e = new ResourceAccessException("I/O error", new HttpConnectTimeoutException("timeout"));
		assertThat(GeminiRecipeClient.classifyFailureReason(e, null)).isEqualTo("TIMEOUT");
	}

	@Test
	void classifiesOtherIoFailureAsClientErrorWhenNoStatus() {
		var e = new ResourceAccessException("I/O error", new IOException("boom"));
		assertThat(GeminiRecipeClient.classifyFailureReason(e, null)).isEqualTo("CLIENT_ERROR");
	}

	private Map<String, Object> envelope(List<? extends Map<String, ?>> parts, String finishReason) {
		return Map.of("candidates", List.of(Map.of("finishReason", finishReason,
			"content", Map.of("parts", parts))));
	}
}
