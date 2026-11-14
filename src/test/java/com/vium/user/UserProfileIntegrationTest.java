package com.vium.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.auth.service.TokenService;
import com.vium.global.security.JwtProperties;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
	"spring.datasource.url=jdbc:h2:mem:profile-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
	"images.storage.public-base-url=https://images.example.com"
})
@AutoConfigureMockMvc
@Sql(scripts = "/db/auth-users.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class UserProfileIntegrationTest {
	@Autowired private MockMvc mockMvc;
	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private TokenService tokenService;
	@Autowired private JwtProperties jwtProperties;
	@Autowired private JsonMapper mapper;
	@MockitoBean private Clock authClock;

	private static final Instant NOW = Instant.parse("2026-09-30T00:00:00Z");
	private TokenService.TokenPair tokens;

	@BeforeEach
	void setUp() {
		when(authClock.instant()).thenReturn(NOW);
		jdbcTemplate.update("delete from user_sessions");
		jdbcTemplate.update("update users set profile_image_id = null");
		jdbcTemplate.update("delete from uploaded_images");
		jdbcTemplate.update("delete from users");
		jdbcTemplate.update("""
			insert into users (id,email,display_name,password_hash,created_at,updated_at)
			values (42,'profile@example.com','Profile User','secret-hash',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
			       (43,'other@example.com','Other User',null,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
			""");
		tokens = tokenService.issue(42L);
	}

	@Test
	void returnsOnlyCurrentUserIdentityWithoutCaching() throws Exception {
		profile(tokens.accessToken()).andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$", aMapWithSize(3)))
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data", aMapWithSize(4)))
			.andExpect(jsonPath("$.data.id").value(42))
			.andExpect(jsonPath("$.data.email").value("profile@example.com"))
			.andExpect(jsonPath("$.data.displayName").value("Profile User"))
			.andExpect(jsonPath("$.data.profileImageUrl").value(nullValue()))
			.andExpect(jsonPath("$.error").value(nullValue()));
	}

	@Test
	void returnsIdentityForUserWithoutPassword() throws Exception {
		profile(tokenService.issue(43L).accessToken()).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.id").value(43))
			.andExpect(jsonPath("$.data.email").value("other@example.com"))
			.andExpect(jsonPath("$.data.displayName").value("Other User"));
	}

	@Test
	void queryParameterCannotOverrideAuthenticatedUser() throws Exception {
		mockMvc.perform(get("/api/me").param("userId", "43")
				.header("Authorization", "Bearer " + tokens.accessToken()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(42));
	}

	@Test
	void returnsLatestDatabaseValuesAfterTokenIssuance() throws Exception {
		jdbcTemplate.update("update users set display_name = ?, email = ? where id = 42",
			"Updated Name", "updated@example.com");
		profile(tokens.accessToken()).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.email").value("updated@example.com"))
			.andExpect(jsonPath("$.data.displayName").value("Updated Name"));
	}

	@Test
	void returnsProfileImageUrlWhenSet() throws Exception {
		long imageId = insertUploadedImage(42L, "PROFILE", true);
		jdbcTemplate.update("update users set profile_image_id = ? where id = 42", imageId);
		profile(tokens.accessToken()).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.profileImageUrl")
				.value(startsWith("https://images.example.com/images/42/")));
	}

	@Test
	void rejectsMissingUser() throws Exception {
		assertUnauthorized(profile(tokenService.issue(999L).accessToken()));
	}

	@Test
	void rejectsUserDeletedAfterTokenIssuance() throws Exception {
		jdbcTemplate.update("update users set deleted_at = CURRENT_TIMESTAMP where id = 42");
		assertUnauthorized(profile(tokens.accessToken()));
	}

	@ParameterizedTest
	@ValueSource(strings = {"missing", "malformed", "expired", "refresh"})
	void requiresValidAccessToken(String kind) throws Exception {
		var request = get("/api/me");
		if (kind.equals("malformed")) {
			request.header("Authorization", "Bearer invalid-token");
		} else if (kind.equals("expired")) {
			when(authClock.instant()).thenReturn(NOW.plusSeconds(
				jwtProperties.accessTokenSeconds() + jwtProperties.clockSkewSeconds() + 1));
			request.header("Authorization", "Bearer " + tokens.accessToken());
		} else if (kind.equals("refresh")) {
			request.header("Authorization", "Bearer " + tokens.refreshToken());
		}
		assertUnauthorized(mockMvc.perform(request));
	}

	@Test
	void updatesDisplayNameOnly() throws Exception {
		jdbcTemplate.update("update users set updated_at = ? where id = 42",
			Timestamp.valueOf("2020-01-01 00:00:00"));
		patchProfile(Map.of("displayName", "New Name")).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.displayName").value("New Name"))
			.andExpect(jsonPath("$.data.email").value("profile@example.com"))
			.andExpect(jsonPath("$.data.profileImageUrl").value(nullValue()));
		assertThatDisplayNameIs("New Name");
		assertThatUpdatedAtMatchesClock();
	}

	@Test
	void updatesProfileImageIdOnly() throws Exception {
		long imageId = insertUploadedImage(42L, "PROFILE", true);
		patchProfile(Map.of("profileImageId", imageId)).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.displayName").value("Profile User"))
			.andExpect(jsonPath("$.data.profileImageUrl")
				.value(startsWith("https://images.example.com/images/42/")));
		assertThatDisplayNameIs("Profile User");
		assertThat(jdbcTemplate.queryForObject("select profile_image_id from users where id = 42", Long.class))
			.isEqualTo(imageId);
	}

	@Test
	void updatesBothFieldsTogether() throws Exception {
		long imageId = insertUploadedImage(42L, "PROFILE", true);
		patchProfile(new HashMap<>(Map.of("displayName", "New Name", "profileImageId", imageId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.displayName").value("New Name"))
			.andExpect(jsonPath("$.data.profileImageUrl")
				.value(startsWith("https://images.example.com/images/42/")));
	}

	@Test
	void rejectsEmptyBody() throws Exception {
		patchProfile(Map.of()).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	void rejectsBothFieldsExplicitlyNull() throws Exception {
		var body = new HashMap<String, Object>();
		body.put("displayName", null);
		body.put("profileImageId", null);
		patchProfile(body).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	void rejectsBlankDisplayName() throws Exception {
		patchProfile(Map.of("displayName", "   ")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
		assertThatDisplayNameIs("Profile User");
	}

	@Test
	void rejectsOversizedDisplayName() throws Exception {
		patchProfile(Map.of("displayName", "a".repeat(81))).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
		assertThatDisplayNameIs("Profile User");
	}

	@Test
	void trimsDisplayNameBeforeValidationAndStorage() throws Exception {
		patchProfile(Map.of("displayName", "　 새 이름 　")).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.displayName").value("새 이름"));
		assertThatDisplayNameIs("새 이름");
	}

	@Test
	void rejectsOtherUsersImage() throws Exception {
		long imageId = insertUploadedImage(43L, "PROFILE", true);
		patchProfile(Map.of("profileImageId", imageId)).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("IMAGE_NOT_FOUND"));
		assertThat(jdbcTemplate.queryForObject("select profile_image_id from users where id = 42", Long.class))
			.isNull();
	}

	@Test
	void rejectsNonProfilePurposeImage() throws Exception {
		long imageId = insertUploadedImage(42L, "RECEIPT", true);
		patchProfile(Map.of("profileImageId", imageId)).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("IMAGE_NOT_FOUND"));
	}

	@Test
	void rejectsNotReadyImage() throws Exception {
		long imageId = insertUploadedImage(42L, "PROFILE", false);
		patchProfile(Map.of("profileImageId", imageId)).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("IMAGE_NOT_FOUND"));
	}

	@Test
	void rejectsUpdateForDeletedUser() throws Exception {
		jdbcTemplate.update("update users set deleted_at = CURRENT_TIMESTAMP where id = 42");
		assertUnauthorized(patchProfile(Map.of("displayName", "New Name")));
	}

	@Test
	void requiresValidAccessTokenForPatch() throws Exception {
		mockMvc.perform(patch("/api/me").contentType(MediaType.APPLICATION_JSON)
				.content(mapper.writeValueAsString(Map.of("displayName", "New Name"))))
			.andExpect(status().isUnauthorized());
	}

	private ResultActions profile(String token) throws Exception {
		return mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + token));
	}

	private ResultActions patchProfile(Map<String, ?> body) throws Exception {
		return mockMvc.perform(patch("/api/me").contentType(MediaType.APPLICATION_JSON)
			.header("Authorization", "Bearer " + tokens.accessToken())
			.content(mapper.writeValueAsString(body)));
	}

	private long insertUploadedImage(long userId, String purpose, boolean ready) {
		var keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
		String objectKey = "images/" + userId + "/" + java.util.UUID.randomUUID() + ".png";
		jdbcTemplate.update(connection -> {
			var statement = connection.prepareStatement("""
				insert into uploaded_images (user_id, purpose, bucket, object_key, content_type,
					size_bytes, created_at, ready)
				values (?, ?, 'test-public', ?, 'image/png', 100, CURRENT_TIMESTAMP, ?)
				""", new String[] {"id"});
			statement.setLong(1, userId);
			statement.setString(2, purpose);
			statement.setString(3, objectKey);
			statement.setBoolean(4, ready);
			return statement;
		}, keyHolder);
		return java.util.Objects.requireNonNull(keyHolder.getKey()).longValue();
	}

	private void assertThatDisplayNameIs(String expected) {
		assertThat(jdbcTemplate.queryForObject("select display_name from users where id = 42", String.class))
			.isEqualTo(expected);
	}

	private void assertThatUpdatedAtMatchesClock() {
		var updatedAt = jdbcTemplate.queryForObject("select updated_at from users where id = 42", Timestamp.class);
		assertThat(updatedAt.toInstant()).isEqualTo(NOW);
	}

	private void assertUnauthorized(ResultActions result) throws Exception {
		result.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").value(nullValue()))
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
	}
}
