package com.vium.global.exception;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class GlobalExceptionHandlerIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

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
}
