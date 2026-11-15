package com.vium.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.auth.service.TokenService;
import com.vium.user.service.UserSettingsService;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:settings-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@Sql(scripts = "/db/auth-users.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class UserSettingsIntegrationTest {
	@Autowired private MockMvc mockMvc;
	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private TokenService tokenService;
	@Autowired private UserSettingsService userSettingsService;
	@Autowired private JsonMapper mapper;
	@MockitoBean private Clock authClock;

	private static final Instant NOW = Instant.parse("2026-09-30T00:00:00Z");
	private TokenService.TokenPair tokens;

	@BeforeEach
	void setUp() {
		when(authClock.instant()).thenReturn(NOW);
		jdbcTemplate.update("delete from user_sessions");
		jdbcTemplate.update("delete from users");
		jdbcTemplate.update("""
			insert into users (id,email,display_name,password_hash,created_at,updated_at)
			values (42,'settings@example.com','Settings User','secret-hash',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
			""");
		tokens = tokenService.issue(42L);
	}

	@Test
	void returnsDefaultSettings() throws Exception {
		settings().andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.expiryAlertDays").value(2))
			.andExpect(jsonPath("$.data.autoDisposeEnabled").value(true))
			.andExpect(jsonPath("$.data.shoppingReminderEnabled").value(false))
			.andExpect(jsonPath("$.data.pushEnabled").value(true))
			.andExpect(jsonPath("$.error").value(nullValue()));
	}

	@Test
	void updatesOnlyGivenField() throws Exception {
		patchSettings(Map.of("expiryAlertDays", 5)).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.expiryAlertDays").value(5))
			.andExpect(jsonPath("$.data.autoDisposeEnabled").value(true))
			.andExpect(jsonPath("$.data.shoppingReminderEnabled").value(false))
			.andExpect(jsonPath("$.data.pushEnabled").value(true));
		assertThat(jdbcTemplate.queryForObject("select expiry_alert_days from users where id = 42", Integer.class))
			.isEqualTo(5);
	}

	@Test
	void updatesMultipleFieldsTogether() throws Exception {
		patchSettings(Map.of("autoDisposeEnabled", false, "pushEnabled", false)).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.expiryAlertDays").value(2))
			.andExpect(jsonPath("$.data.autoDisposeEnabled").value(false))
			.andExpect(jsonPath("$.data.shoppingReminderEnabled").value(false))
			.andExpect(jsonPath("$.data.pushEnabled").value(false));
	}

	@Test
	void keepsOmittedFieldsUnchanged() throws Exception {
		patchSettings(Map.of("shoppingReminderEnabled", true)).andExpect(status().isOk());
		patchSettings(Map.of("expiryAlertDays", 10)).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.expiryAlertDays").value(10))
			.andExpect(jsonPath("$.data.shoppingReminderEnabled").value(true));
	}

	@Test
	void rejectsEmptyBody() throws Exception {
		patchSettings(Map.of()).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	void rejectsExpiryAlertDaysBelowMinimum() throws Exception {
		patchSettings(Map.of("expiryAlertDays", 0)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
		assertThat(jdbcTemplate.queryForObject("select expiry_alert_days from users where id = 42", Integer.class))
			.isEqualTo(2);
	}

	@Test
	void rejectsExpiryAlertDaysAboveMaximum() throws Exception {
		patchSettings(Map.of("expiryAlertDays", 15)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
		assertThat(jdbcTemplate.queryForObject("select expiry_alert_days from users where id = 42", Integer.class))
			.isEqualTo(2);
	}

	@Test
	void updatedSettingsAreReflectedInGetExpiryAlertDays() throws Exception {
		patchSettings(Map.of("expiryAlertDays", 7)).andExpect(status().isOk());
		assertThat(userSettingsService.getExpiryAlertDays(42L)).isEqualTo(7);
	}

	@Test
	void requiresValidAccessTokenForGet() throws Exception {
		mockMvc.perform(get("/api/me/settings")).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
	}

	@Test
	void requiresValidAccessTokenForPatch() throws Exception {
		mockMvc.perform(patch("/api/me/settings").contentType(MediaType.APPLICATION_JSON)
				.content(mapper.writeValueAsString(Map.of("expiryAlertDays", 3))))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
	}

	private ResultActions settings() throws Exception {
		return mockMvc.perform(get("/api/me/settings").header("Authorization", "Bearer " + tokens.accessToken()));
	}

	private ResultActions patchSettings(Map<String, ?> body) throws Exception {
		return mockMvc.perform(patch("/api/me/settings").contentType(MediaType.APPLICATION_JSON)
			.header("Authorization", "Bearer " + tokens.accessToken())
			.content(mapper.writeValueAsString(body)));
	}
}
