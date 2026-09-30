package com.vium.recipe.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vium.recipe.config.LlmProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

	private Map<String, Object> envelope(List<? extends Map<String, ?>> parts, String finishReason) {
		return Map.of("candidates", List.of(Map.of("finishReason", finishReason,
			"content", Map.of("parts", parts))));
	}
}
