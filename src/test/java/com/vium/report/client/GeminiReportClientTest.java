package com.vium.report.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vium.recipe.config.LlmProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GeminiReportClientTest {

	private final GeminiReportClient client = new GeminiReportClient(new LlmProperties(), JsonMapper.builder().build(), null);

	private static final String VALID_JSON = """
		{"summary":"10월에는 잎채소 폐기가 많았어요.",
		"insights":[{"title":"잎채소를 자주 버려요","body":"상추를 자주 폐기했어요."}],
		"suggestions":["다음에는 상추를 절반만 사보세요."]}
		""";

	@Test
	void parsesValidReportFromGeminiEnvelope() {
		var result = client.parseGeminiResponse(envelope(List.of(Map.of("text", VALID_JSON)), "STOP"));
		assertThat(result.summary()).isEqualTo("10월에는 잎채소 폐기가 많았어요.");
		assertThat(result.insights()).singleElement().satisfies(insight -> {
			assertThat(insight.title()).isEqualTo("잎채소를 자주 버려요");
			assertThat(insight.body()).isEqualTo("상추를 자주 폐기했어요.");
		});
		assertThat(result.suggestions()).containsExactly("다음에는 상추를 절반만 사보세요.");
	}

	@Test
	void rejectsEmptySummary() {
		String json = """
			{"summary":"","insights":[{"title":"t","body":"b"}],"suggestions":["s"]}
			""";
		assertThatThrownBy(() -> client.parseGeminiResponse(envelope(List.of(Map.of("text", json)), "STOP")))
			.isInstanceOf(RuntimeException.class).hasMessage("응답 파싱 실패");
	}

	@Test
	void rejectsTooManyInsights() {
		String insight = "{\"title\":\"t\",\"body\":\"b\"}";
		String insights = String.join(",", java.util.Collections.nCopies(6, insight));
		String json = "{\"summary\":\"s\",\"insights\":[" + insights + "],\"suggestions\":[\"s\"]}";
		assertThatThrownBy(() -> client.parseGeminiResponse(envelope(List.of(Map.of("text", json)), "STOP")))
			.isInstanceOf(RuntimeException.class).hasMessage("응답 파싱 실패");
	}

	@Test
	void rejectsInsightTitleOverSixtyChars() {
		String longTitle = "가".repeat(61);
		String json = "{\"summary\":\"s\",\"insights\":[{\"title\":\"" + longTitle
			+ "\",\"body\":\"b\"}],\"suggestions\":[\"s\"]}";
		assertThatThrownBy(() -> client.parseGeminiResponse(envelope(List.of(Map.of("text", json)), "STOP")))
			.isInstanceOf(RuntimeException.class).hasMessage("응답 파싱 실패");
	}

	@Test
	void rejectsInsightBodyOverThreeHundredChars() {
		String longBody = "가".repeat(301);
		String json = "{\"summary\":\"s\",\"insights\":[{\"title\":\"t\",\"body\":\"" + longBody
			+ "\"}],\"suggestions\":[\"s\"]}";
		assertThatThrownBy(() -> client.parseGeminiResponse(envelope(List.of(Map.of("text", json)), "STOP")))
			.isInstanceOf(RuntimeException.class).hasMessage("응답 파싱 실패");
	}

	@Test
	void rejectsSuggestionOverTwoHundredChars() {
		String longSuggestion = "가".repeat(201);
		String json = "{\"summary\":\"s\",\"insights\":[{\"title\":\"t\",\"body\":\"b\"}],\"suggestions\":[\""
			+ longSuggestion + "\"]}";
		assertThatThrownBy(() -> client.parseGeminiResponse(envelope(List.of(Map.of("text", json)), "STOP")))
			.isInstanceOf(RuntimeException.class).hasMessage("응답 파싱 실패");
	}

	@Test
	void rejectsEmptySuggestionsOrTooManySuggestions() {
		String emptyJson = "{\"summary\":\"s\",\"insights\":[{\"title\":\"t\",\"body\":\"b\"}],\"suggestions\":[]}";
		assertThatThrownBy(() -> client.parseGeminiResponse(envelope(List.of(Map.of("text", emptyJson)), "STOP")))
			.isInstanceOf(RuntimeException.class).hasMessage("응답 파싱 실패");

		String suggestion = "\"s\"";
		String suggestions = String.join(",", java.util.Collections.nCopies(6, suggestion));
		String tooManyJson = "{\"summary\":\"s\",\"insights\":[{\"title\":\"t\",\"body\":\"b\"}],\"suggestions\":["
			+ suggestions + "]}";
		assertThatThrownBy(() -> client.parseGeminiResponse(envelope(List.of(Map.of("text", tooManyJson)), "STOP")))
			.isInstanceOf(RuntimeException.class).hasMessage("응답 파싱 실패");
	}

	@Test
	void rejectsMissingCandidatesAndBlockedOrTruncatedResponses() {
		assertThatThrownBy(() -> client.parseGeminiResponse(Map.of())).isInstanceOf(RuntimeException.class);
		assertThatThrownBy(() -> client.parseGeminiResponse(null)).isInstanceOf(RuntimeException.class);
		assertThatThrownBy(() -> client.parseGeminiResponse(envelope(List.of(Map.of("text", VALID_JSON)), "MAX_TOKENS")))
			.isInstanceOf(RuntimeException.class);
	}

	private Map<String, Object> envelope(List<? extends Map<String, ?>> parts, String finishReason) {
		return Map.of("candidates", List.of(Map.of("finishReason", finishReason,
			"content", Map.of("parts", parts))));
	}
}
