package com.vium.global.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@Import(GlobalExceptionHandlerIntegrationTest.FailingController.class)
class GlobalExceptionHandlerIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private MeterRegistry meterRegistry;

	@Test
	void unhandledExceptionLogsStackTraceWithoutExposingDetails(CapturedOutput output) throws Exception {
		int outputStart = output.getOut().length();
		var result = mockMvc.perform(get("/api/test/unhandled?token=query-secret").with(jwt()))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").value(nullValue()))
			.andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
			.andExpect(jsonPath("$.error.message").value("서버 오류가 발생했습니다."))
			.andReturn();
		assertThat(result.getResponse().getContentAsString())
			.doesNotContain("unhandled-detail", "IllegalStateException");
		assertThat(output.getOut().substring(outputStart)).contains("ERROR", "처리되지 않은 예외: GET /api/test/unhandled",
			"java.lang.IllegalStateException: unhandled-detail",
			"at com.vium.global.exception.GlobalExceptionHandlerIntegrationTest$FailingController")
			.doesNotContain("query-secret");
	}

	@Test
	void unhandledExceptionRecordsExceptionAndErrorMetricTags() throws Exception {
		mockMvc.perform(get("/api/test/unhandled").with(jwt()))
			.andExpect(status().isInternalServerError());
		assertThat(meterRegistry.get("http.server.requests")
			.tags("uri", "/api/test/unhandled", "status", "500", "method", "GET",
				"outcome", "SERVER_ERROR", "exception", "IllegalStateException", "error", "IllegalStateException")
			.timer().count()).isGreaterThanOrEqualTo(1);
	}

	@Test
	void serverBusinessExceptionLogsCauseWithoutChangingMetricTags(CapturedOutput output) throws Exception {
		int outputStart = output.getOut().length();
		mockMvc.perform(get("/api/test/storage-unavailable").with(jwt()))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.error.code").value("IMAGE_STORAGE_UNAVAILABLE"))
			.andExpect(jsonPath("$.error.message").value(ErrorCode.IMAGE_STORAGE_UNAVAILABLE.getDefaultMessage()));
		assertThat(output.getOut().substring(outputStart)).contains("WARN", "code=IMAGE_STORAGE_UNAVAILABLE",
			"Caused by: java.lang.IllegalStateException: s3-down");
		assertThat(meterRegistry.get("http.server.requests")
			.tags("uri", "/api/test/storage-unavailable", "status", "503", "exception", "none", "error", "none")
			.timer().count()).isGreaterThanOrEqualTo(1);
	}

	@Test
	void clientErrorsDoNotLogFromExceptionHandler(CapturedOutput output) throws Exception {
		int outputStart = output.getOut().length();
		mockMvc.perform(get("/api/me/nope").with(jwt())).andExpect(status().isNotFound());
		mockMvc.perform(delete("/api/health").with(jwt())).andExpect(status().isMethodNotAllowed());
		mockMvc.perform(get("/api/test/business-client-error").with(jwt()))
			.andExpect(status().isBadRequest());
		assertThat(output.getOut().substring(outputStart)).doesNotContain("GlobalExceptionHandler");
	}


	@Test
	void authenticatedMissingPathReturnsNotFound() throws Exception {
		mockMvc.perform(get("/api/me/nope").with(jwt()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").value(nullValue()))
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.message").value("리소스를 찾을 수 없습니다."));
	}

	@Test
	void unauthenticatedMissingPathReturnsUnauthorized() throws Exception {
		mockMvc.perform(get("/api/me/nope"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
	}

	@Test
	void unsupportedMethodReturnsMethodNotAllowedWithAllowHeader() throws Exception {
		mockMvc.perform(delete("/api/health").with(jwt()))
			.andExpect(status().isMethodNotAllowed())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").value(nullValue()))
			.andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"))
			.andExpect(jsonPath("$.error.message").value("지원하지 않는 HTTP 메서드입니다."))
			.andExpect(header().string(HttpHeaders.ALLOW, containsString("GET")));
	}
	@Test
	void internalBusinessExceptionLogsAtErrorLevel(CapturedOutput output) throws Exception {
		int outputStart = output.getOut().length();
		mockMvc.perform(get("/api/test/internal-business-error").with(jwt()))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
		assertThat(output.getOut().substring(outputStart))
			.contains("ERROR", "서버 측 비즈니스 오류: code=INTERNAL_ERROR")
			.doesNotContain("WARN");
		assertThat(meterRegistry.get("http.server.requests")
			.tags("uri", "/api/test/internal-business-error", "status", "500", "exception", "none")
			.timer().count()).isGreaterThanOrEqualTo(1);
	}

	@ParameterizedTest
	@ValueSource(strings = {"async", "tomcat", "wrapped"})
	void disconnectedClientDoesNotRewriteResponseOrRecordServerError(String kind, CapturedOutput output)
		throws Exception {
		int outputStart = output.getOut().length();
		var result = mockMvc.perform(get("/api/test/disconnected/{kind}", kind).with(jwt()))
			.andExpect(status().isNoContent()).andReturn();
		assertThat(result.getResponse().getContentAsString()).isEmpty();
		assertThat(output.getOut().substring(outputStart)).doesNotContain("ERROR", "WARN", "GlobalExceptionHandler");
		assertThat(meterRegistry.get("http.server.requests")
			.tags("uri", "/api/test/disconnected/{kind}", "status", "204", "exception", "none", "error", "none")
			.timer().count()).isGreaterThanOrEqualTo(1);
		assertThat(meterRegistry.find("http.server.requests")
			.tags("uri", "/api/test/disconnected/{kind}", "status", "500").timers()).isEmpty();
	}

	@Test
	void upstreamConnectionFailureStillRecordsServerError(CapturedOutput output) throws Exception {
		int outputStart = output.getOut().length();
		mockMvc.perform(get("/api/test/upstream-failure").with(jwt()))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
		assertThat(output.getOut().substring(outputStart)).contains("ERROR", "ResourceAccessException");
		assertThat(meterRegistry.get("http.server.requests")
			.tags("uri", "/api/test/upstream-failure", "status", "500", "exception", "ResourceAccessException")
			.timer().count()).isGreaterThanOrEqualTo(1);
	}

	@RestController
	static class FailingController {
		@GetMapping("/api/test/internal-business-error")
		String internalBusinessError() {
			throw new BusinessException(ErrorCode.INTERNAL_ERROR);
		}

		@GetMapping("/api/test/disconnected/{kind}")
		void disconnected(@PathVariable String kind, HttpServletResponse response) throws IOException {
			response.setStatus(HttpServletResponse.SC_NO_CONTENT);
			response.flushBuffer();
			throw switch (kind) {
				case "async" -> new AsyncRequestNotUsableException("Response is not usable");
				case "tomcat" -> new ClientAbortException("Connection reset by peer");
				default -> new IOException("Response write failed", new ClientAbortException("Broken pipe"));
			};
		}

		@GetMapping("/api/test/upstream-failure")
		String upstreamFailure() {
			throw new ResourceAccessException("Upstream request failed", new IOException("Connection reset by peer"));
		}

		@GetMapping("/api/test/unhandled")
		String unhandled() {
			throw new IllegalStateException("unhandled-detail");
		}

		@GetMapping("/api/test/storage-unavailable")
		String storageUnavailable() {
			throw new BusinessException(ErrorCode.IMAGE_STORAGE_UNAVAILABLE, new IllegalStateException("s3-down"));
		}

		@GetMapping("/api/test/business-client-error")
		String businessClientError() {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
	}

}
