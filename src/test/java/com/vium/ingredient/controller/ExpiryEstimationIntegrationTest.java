package com.vium.ingredient.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.vium.auth.service.TokenService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:expiry-estimation-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Transactional
class ExpiryEstimationIntegrationTest {

	private static final String URL = "/api/ingredients/estimate-expiry";

	@Autowired private MockMvc mvc;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private TokenService tokenService;
	@MockitoBean private Clock clock;

	@BeforeEach
	void setUp() {
		when(clock.instant()).thenReturn(Instant.parse("2026-11-24T15:30:00Z"));
		when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
		jdbc.update("insert into units (id, code, name) values (1, 'ea', '개')");
		jdbc.update("insert into item_statuses (id, code, name) values (1, 'active', '보유')");
		jdbc.update("""
			insert into storage_methods (id, code, name)
			values (1, 'cold', '냉장'), (2, 'frozen', '냉동'), (3, 'room', '실온')
			""");
		jdbc.update("""
			insert into ingredient_catalog (id, default_unit_id, name, created_at)
			values (5, 1, '대파', current_timestamp), (6, 1, '감자', current_timestamp),
			       (7, 1, '규칙 없는 재료', current_timestamp)
			""");
		jdbc.update("""
			insert into expiry_estimation_rules
				(ingredient_catalog_id, storage_method_id, shelf_life_days, effective_from, effective_to)
			values (5, 1, 7, DATE '2020-01-01', null),
			       (5, 1, 12, DATE '2026-02-01', DATE '2026-02-10'),
			       (6, 3, 30, DATE '2020-01-01', null),
			       (6, 2, 90, DATE '2020-01-01', null)
			""");
	}

	@Test
	void estimatesUsingOnlyTheRequestedStorageMethod() throws Exception {
		mvc.perform(preview().param("ingredientCatalogId", "5").param("storageMethodId", "1")
				.param("purchasedOn", "2026-01-15"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.ingredientCatalogId").value(5))
			.andExpect(jsonPath("$.data.ingredientName").value("대파"))
			.andExpect(jsonPath("$.data.storageMethodId").value(1))
			.andExpect(jsonPath("$.data.storageMethodName").value("냉장"))
			.andExpect(jsonPath("$.data.purchasedOn").value("2026-01-15"))
			.andExpect(jsonPath("$.data.shelfLifeDays").value(7))
			.andExpect(jsonPath("$.data.estimatedExpiryDate").value("2026-01-22"));
	}

	@Test
	void defaultsToSeoulTodayAndTheFirstStorageMethodWithARule() throws Exception {
		mvc.perform(preview().param("ingredientCatalogId", "6"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.purchasedOn").value("2026-11-25"))
			.andExpect(jsonPath("$.data.storageMethodId").value(3))
			.andExpect(jsonPath("$.data.storageMethodName").value("실온"))
			.andExpect(jsonPath("$.data.shelfLifeDays").value(30))
			.andExpect(jsonPath("$.data.estimatedExpiryDate").value("2026-12-25"));
	}

	@Test
	void returnsNullEstimateAndKeepsRequestedStorageWithoutFallingBack() throws Exception {
		mvc.perform(preview().param("ingredientCatalogId", "6").param("storageMethodId", "1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.storageMethodId").value(1))
			.andExpect(jsonPath("$.data.storageMethodName").value("냉장"))
			.andExpect(jsonPath("$.data.shelfLifeDays").value(nullValue()))
			.andExpect(jsonPath("$.data.estimatedExpiryDate").value(nullValue()));
	}

	@Test
	void returnsNullStorageAndEstimateWhenNoDefaultRuleApplies() throws Exception {
		mvc.perform(preview().param("ingredientCatalogId", "7"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.storageMethodId").value(nullValue()))
			.andExpect(jsonPath("$.data.storageMethodName").value(nullValue()))
			.andExpect(jsonPath("$.data.shelfLifeDays").value(nullValue()))
			.andExpect(jsonPath("$.data.estimatedExpiryDate").value(nullValue()));
	}

	@ParameterizedTest
	@CsvSource({"2026-01-31,7", "2026-02-01,12", "2026-02-10,12", "2026-02-11,7"})
	void appliesLatestRuleWithInclusiveEffectiveDateBoundaries(String purchasedOn, int days) throws Exception {
		mvc.perform(preview().param("ingredientCatalogId", "5").param("storageMethodId", "1")
				.param("purchasedOn", purchasedOn))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.shelfLifeDays").value(days));
	}

	@Test
	void registrationSavesThePreviewDateForTheSameInputs() throws Exception {
		String previewBody = mvc.perform(preview().param("ingredientCatalogId", "5").param("purchasedOn", "2026-02-10"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		int storageMethodId = JsonPath.read(previewBody, "$.data.storageMethodId");
		String purchasedOn = JsonPath.read(previewBody, "$.data.purchasedOn");
		String expectedExpiry = JsonPath.read(previewBody, "$.data.estimatedExpiryDate");

		mvc.perform(post("/api/me/ingredients").header("Authorization", bearer())
				.contentType(MediaType.APPLICATION_JSON).content("""
					{"ingredientCatalogId":5,"unitId":1,"quantity":1,"storageMethodId":%d,"purchasedOn":"%s"}
					""".formatted(storageMethodId, purchasedOn)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.expiresOn").value(expectedExpiry));
		assertThat(jdbc.queryForObject("select expires_on from inventory_items where ingredient_catalog_id = 5",
			LocalDate.class)).isEqualTo(LocalDate.parse(expectedExpiry));
	}

	@Test
	void returnsNotFoundForUnknownCatalog() throws Exception {
		mvc.perform(preview().param("ingredientCatalogId", "999"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.message").value("존재하지 않는 ingredientCatalogId 입니다"));
	}

	@Test
	void rejectsUnknownStorageMethod() throws Exception {
		mvc.perform(preview().param("ingredientCatalogId", "5").param("storageMethodId", "999"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.error.message").value("존재하지 않는 storageMethodId 입니다"));
	}

	@Test
	void rejectsMissingCatalogParameter() throws Exception {
		mvc.perform(preview()).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.error.message").value("ingredientCatalogId: 필수 요청 파라미터입니다"));
	}

	@Test
	void rejectsFuturePurchaseDate() throws Exception {
		mvc.perform(preview().param("ingredientCatalogId", "5").param("purchasedOn", "2026-11-26"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.error.message").value("purchasedOn은 미래일 수 없습니다"));
	}

	@ParameterizedTest
	@CsvSource({"ingredientCatalogId,not-a-number", "storageMethodId,not-a-number", "purchasedOn,2026-02-30"})
	void rejectsMalformedParameters(String parameter, String value) throws Exception {
		var request = preview().param(parameter, value);
		if (!parameter.equals("ingredientCatalogId")) {
			request.param("ingredientCatalogId", "5");
		}
		mvc.perform(request).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.error.message").value(parameter + ": 값의 형식이 올바르지 않습니다"));
	}

	@Test
	void requiresAuthentication() throws Exception {
		mvc.perform(get(URL).param("ingredientCatalogId", "5"))
			.andExpect(status().isUnauthorized());
	}

	private MockHttpServletRequestBuilder preview() {
		return get(URL).header("Authorization", bearer());
	}

	private String bearer() {
		return "Bearer " + tokenService.issue(1L).accessToken();
	}
}
