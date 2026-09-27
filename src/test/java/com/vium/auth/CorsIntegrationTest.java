package com.vium.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "CORS_ALLOWED_ORIGINS=https://app.example.com")
@AutoConfigureMockMvc
class CorsIntegrationTest {
	@Autowired private MockMvc mockMvc;

	@Test
	void allowsPreflightForBearerRequestWithoutAuthentication() throws Exception {
		mockMvc.perform(options("/api/me/ingredients")
			.header("Origin", "https://app.example.com")
			.header("Access-Control-Request-Method", "PATCH")
			.header("Access-Control-Request-Headers", "authorization,content-type"))
			.andExpect(status().isOk())
			.andExpect(header().string("Access-Control-Allow-Origin", "https://app.example.com"))
			.andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
	}

	@Test
	void rejectsUnlistedOrigin() throws Exception {
		mockMvc.perform(options("/api/auth/login")
			.header("Origin", "https://untrusted.example.com")
			.header("Access-Control-Request-Method", "POST"))
			.andExpect(status().isForbidden())
			.andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
	}

	@Test
	void allowedOriginStillRequiresBearerAndCanReadUnauthorizedResponse() throws Exception {
		mockMvc.perform(get("/api/me/ingredients").header("Origin", "https://app.example.com"))
			.andExpect(status().isUnauthorized())
			.andExpect(header().string("Access-Control-Allow-Origin", "https://app.example.com"));
	}
}
