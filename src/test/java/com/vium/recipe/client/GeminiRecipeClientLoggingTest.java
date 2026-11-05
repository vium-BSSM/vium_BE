package com.vium.recipe.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.vium.recipe.config.LlmProperties;
import com.vium.recipe.dto.LlmRecipeDetailRequest;
import com.vium.recipe.dto.LlmRecipeRequest;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class GeminiRecipeClientLoggingTest {
	private static final String SECRET = "test-secret-api-key";
	private static final String PRIVATE_TEXT = "private-user-ingredient";
	private static final UUID BATCH_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private final Logger logger = (Logger) LoggerFactory.getLogger(GeminiRecipeClient.class);
	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
	private final LlmRecipeRequest request = new LlmRecipeRequest(BATCH_ID, List.of(), Map.of("KOREAN", 1), List.of());
	private final LlmRecipeDetailRequest detailRequest = new LlmRecipeDetailRequest(42L, PRIVATE_TEXT, "KOREAN",
		List.of(new LlmRecipeDetailRequest.IngredientInfo(1L, PRIVATE_TEXT)));
	private MockRestServiceServer server;
	private GeminiRecipeClient client;

	@BeforeEach
	void setUp() {
		appender.start();
		logger.addAppender(appender);
		var builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		var properties = new LlmProperties();
		properties.setApiKey(SECRET);
		properties.setModel("test-model");
		var restClient = builder.build();
		client = new GeminiRecipeClient(properties, JsonMapper.builder().build(), restClient, restClient);
	}

	@AfterEach
	void tearDown() {
		logger.detachAppender(appender);
		appender.stop();
	}

	@Test
	void sendsApiKeyOnlyInHeaderAndLogsBatchIdAndTokensOnSuccess() {
		var envelope = Map.of(
			"candidates", List.of(Map.of("finishReason", "STOP", "content",
				Map.of("parts", List.of(Map.of("text", "{\"recipes\":[]}"))))),
			"usageMetadata", Map.of("promptTokenCount", 50, "candidatesTokenCount", 10, "totalTokenCount", 60));
		server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/test-model:generateContent"))
			.andExpect(header("x-goog-api-key", SECRET))
			.andRespond(withSuccess(JsonMapper.builder().build().writeValueAsString(envelope), MediaType.APPLICATION_JSON));
		assertThat(client.generateRecipes(request).recipes()).isEmpty();
		server.verify();
		assertThat(appender.list).singleElement().satisfies(event -> {
			String message = event.getFormattedMessage();
			assertThat(message).contains("batchId=" + BATCH_ID, "promptTokens=50", "candidatesTokens=10", "totalTokens=60");
			assertThat(message).doesNotContain(SECRET, PRIVATE_TEXT);
		});
	}

	@Test
	void networkFailureDoesNotExposeExceptionContentsAndLogsExactlyOneLine() {
		server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/test-model:generateContent"))
			.andRespond(req -> { throw new IOException(SECRET + PRIVATE_TEXT); });
		assertThatThrownBy(() -> client.generateRecipes(request))
			.hasMessage("LLM 호출 실패").hasNoCause();
		assertSafeSingleFailureLog("batchId=" + BATCH_ID);
	}

	@Test
	void httpFailureLogsReasonAndStatusWithoutErrorBody() {
		server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/test-model:generateContent"))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST).body(SECRET + PRIVATE_TEXT));
		assertThatThrownBy(() -> client.generateRecipes(request)).hasMessage("LLM 호출 실패").hasNoCause();
		assertSafeSingleFailureLog("batchId=" + BATCH_ID, "status=400", "reason=CLIENT_ERROR");
	}

	@Test
	void rateLimitFailureIsClassifiedAsRateLimit() {
		server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/test-model:generateContent"))
			.andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body(SECRET + PRIVATE_TEXT));
		assertThatThrownBy(() -> client.generateRecipes(request)).hasMessage("LLM 호출 실패").hasNoCause();
		assertSafeSingleFailureLog("batchId=" + BATCH_ID, "status=429", "reason=RATE_LIMIT");
	}

	@Test
	void serverErrorFailureIsClassifiedAsUpstreamError() {
		server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/test-model:generateContent"))
			.andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body(SECRET + PRIVATE_TEXT));
		assertThatThrownBy(() -> client.generateRecipes(request)).hasMessage("LLM 호출 실패").hasNoCause();
		assertSafeSingleFailureLog("batchId=" + BATCH_ID, "status=503", "reason=UPSTREAM_ERROR");
	}

	@Test
	void parsingFailureLogsMetadataOnceWithoutRawResponse() {
		var envelope = Map.of("candidates", List.of(Map.of("finishReason", "STOP", "content",
			Map.of("parts", List.of(Map.of("text", "{" + PRIVATE_TEXT + SECRET))))));
		server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/test-model:generateContent"))
			.andRespond(withSuccess(JsonMapper.builder().build().writeValueAsString(envelope), MediaType.APPLICATION_JSON));
		assertThatThrownBy(() -> client.generateRecipes(request)).hasMessage("응답 파싱 실패").hasNoCause();
		assertSafeSingleFailureLog("batchId=" + BATCH_ID, "stage=json", "finishReason=STOP", "textLength=");
	}

	@Test
	void enrichRecipeDetailLogsRecipeIdAndTokensWithoutLeakingPromptContent() {
		var envelope = Map.of(
			"candidates", List.of(Map.of("finishReason", "STOP", "content",
				Map.of("parts", List.of(Map.of("text", "{\"ingredients\":[],\"seasonings\":[],\"steps\":[]}"))))),
			"usageMetadata", Map.of("promptTokenCount", 120, "candidatesTokenCount", 80, "totalTokenCount", 200));
		server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/test-model:generateContent"))
			.andRespond(withSuccess(JsonMapper.builder().build().writeValueAsString(envelope), MediaType.APPLICATION_JSON));

		client.enrichRecipeDetail(detailRequest);

		server.verify();
		assertThat(appender.list).singleElement().satisfies(event -> {
			String message = event.getFormattedMessage();
			assertThat(message).contains("recipeId=42", "promptTokens=120", "candidatesTokens=80", "totalTokens=200");
			assertThat(message).doesNotContain(SECRET, PRIVATE_TEXT);
		});
	}

	@Test
	void enrichRecipeDetailFailureLogsRecipeIdWithoutExposingSecret() {
		server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/test-model:generateContent"))
			.andRespond(req -> { throw new IOException(SECRET + PRIVATE_TEXT); });

		assertThatThrownBy(() -> client.enrichRecipeDetail(detailRequest)).hasMessage("LLM 호출 실패").hasNoCause();

		assertSafeSingleFailureLog("recipeId=42");
	}

	/**
	 * 실패 1건당 로그가 정확히 1줄만 남는지(callGemini/parse 내부 로깅을 없애고 상위에서 한 번만 로깅)
	 * 확인한다. 비밀키·프롬프트 원문은 없어야 하고, 전달한 조각 문자열을 전부 포함해야 한다.
	 */
	private void assertSafeSingleFailureLog(String... expectedFragments) {
		server.verify();
		assertThat(appender.list).singleElement().satisfies(event -> {
			String message = event.getFormattedMessage();
			assertThat(message).doesNotContain(SECRET, PRIVATE_TEXT);
			assertThat(event.getThrowableProxy()).isNull();
			assertThat(message).contains(expectedFragments);
		});
	}
}
