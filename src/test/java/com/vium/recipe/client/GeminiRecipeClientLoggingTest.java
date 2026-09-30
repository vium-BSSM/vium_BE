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
import com.vium.recipe.dto.LlmRecipeRequest;
import java.io.IOException;
import java.util.List;
import java.util.Map;
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
	private final Logger logger = (Logger) LoggerFactory.getLogger(GeminiRecipeClient.class);
	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
	private final LlmRecipeRequest request = new LlmRecipeRequest(List.of(), Map.of("KOREAN", 1), List.of());
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
		client = new GeminiRecipeClient(properties, JsonMapper.builder().build(), builder.build());
	}

	@AfterEach
	void tearDown() {
		logger.detachAppender(appender);
		appender.stop();
	}

	@Test
	void sendsApiKeyOnlyInHeader() {
		var envelope = Map.of("candidates", List.of(Map.of("finishReason", "STOP", "content",
			Map.of("parts", List.of(Map.of("text", "{\"recipes\":[]}"))))));
		server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/test-model:generateContent"))
			.andExpect(header("x-goog-api-key", SECRET))
			.andRespond(withSuccess(JsonMapper.builder().build().writeValueAsString(envelope), MediaType.APPLICATION_JSON));
		assertThat(client.generateRecipes(request).recipes()).isEmpty();
		server.verify();
		assertThat(appender.list).isEmpty();
	}

	@Test
	void networkFailureDoesNotExposeExceptionContents() {
		server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/test-model:generateContent"))
			.andRespond(req -> { throw new IOException(SECRET + PRIVATE_TEXT); });
		assertThatThrownBy(() -> client.generateRecipes(request))
			.hasMessage("LLM 호출 실패").hasNoCause();
		assertSafeSingleLog();
		server.verify();
	}

	@Test
	void httpFailureDoesNotExposeErrorBody() {
		server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/test-model:generateContent"))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST).body(SECRET + PRIVATE_TEXT));
		assertThatThrownBy(() -> client.generateRecipes(request)).hasMessage("LLM 호출 실패").hasNoCause();
		assertSafeSingleLog();
		assertThat(appender.list.getFirst().getFormattedMessage()).contains("status=400");
		server.verify();
	}

	@Test
	void parsingFailureLogsMetadataOnceWithoutRawResponse() {
		var envelope = Map.of("candidates", List.of(Map.of("finishReason", "STOP", "content",
			Map.of("parts", List.of(Map.of("text", "{" + PRIVATE_TEXT + SECRET))))));
		server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/test-model:generateContent"))
			.andRespond(withSuccess(JsonMapper.builder().build().writeValueAsString(envelope), MediaType.APPLICATION_JSON));
		assertThatThrownBy(() -> client.generateRecipes(request)).hasMessage("응답 파싱 실패").hasNoCause();
		assertSafeSingleLog();
		assertThat(appender.list.getFirst().getFormattedMessage())
			.contains("stage=json", "finishReason=STOP", "textLength=");
		server.verify();
	}

	private void assertSafeSingleLog() {
		assertThat(appender.list).singleElement().satisfies(event -> {
			assertThat(event.getFormattedMessage()).doesNotContain(SECRET, PRIVATE_TEXT);
			assertThat(event.getThrowableProxy()).isNull();
		});
	}
}
