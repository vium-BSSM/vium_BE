package com.vium.recipe.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.vium.recipe.config.ImageSearchProperties;
import com.vium.recipe.dto.ImageSearchResult;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class UnsplashImageClientTest {

	private static final String ACCESS_KEY = "test-access-key";

	private MockRestServiceServer server;
	private UnsplashImageClient client;

	@BeforeEach
	void setUp() {
		var builder = RestClient.builder().baseUrl("https://api.unsplash.com");
		server = MockRestServiceServer.bindTo(builder).build();
		var properties = new ImageSearchProperties();
		properties.setAccessKey(ACCESS_KEY);
		client = new UnsplashImageClient(properties, builder.build());
	}

	@Test
	void searchImageReturnsResultOnSuccess() {
		var body = JsonMapper.builder().build().writeValueAsString(java.util.Map.of(
			"results", java.util.List.of(java.util.Map.of(
				"urls", java.util.Map.of("regular", "https://images.unsplash.com/photo-1"),
				"user", java.util.Map.of("name", "author", "links", java.util.Map.of("html", "https://unsplash.com/@author"))))));
		server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.unsplash.com/search/photos")))
			.andExpect(queryParam("query", "kimchi"))
			.andExpect(queryParam("per_page", "1"))
			.andExpect(queryParam("orientation", "landscape"))
			.andExpect(header("Authorization", "Client-ID " + ACCESS_KEY))
			.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

		ImageSearchResult result = client.searchImage("kimchi");

		server.verify();
		assertThat(result.imageUrl()).isEqualTo("https://images.unsplash.com/photo-1");
		assertThat(result.authorName()).isEqualTo("author");
		assertThat(result.authorUrl()).isEqualTo("https://unsplash.com/@author");
	}

	@Test
	void searchImageReturnsNullWhenNoResults() {
		server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.unsplash.com/search/photos")))
			.andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

		assertThat(client.searchImage("존재하지않는재료")).isNull();
	}

	@Test
	void searchImageReturnsNullOnHttpFailure() {
		server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.unsplash.com/search/photos")))
			.andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("invalid key"));

		assertThat(client.searchImage("토마토")).isNull();
	}

	@Test
	void searchImageReturnsNullOnNetworkFailure() {
		server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.unsplash.com/search/photos")))
			.andRespond(req -> { throw new IOException("network down"); });

		assertThat(client.searchImage("양파")).isNull();
	}

	@Test
	void searchImageReturnsNullForBlankQuery() {
		assertThat(client.searchImage(" ")).isNull();
		server.verify();
	}
}
