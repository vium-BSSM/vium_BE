package com.vium.image;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.nullValue;

import com.vium.auth.service.TokenService;
import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.image.dto.ImageAccessResponse;
import com.vium.image.entity.UploadedImage;
import com.vium.image.repository.UploadedImageRepository;
import com.vium.image.service.ImageStorage;
import com.vium.image.service.ImageCleanup;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"spring.datasource.url=jdbc:h2:mem:image-api;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
	"images.storage.enabled=false", "images.storage.private-bucket=test-private", "images.storage.public-bucket=test-public",
	"images.storage.public-base-url=https://images.example.com",
	"spring.servlet.multipart.max-file-size=5MB", "spring.servlet.multipart.max-request-size=6MB",
	"spring.servlet.multipart.file-size-threshold=0"
})
@AutoConfigureMockMvc
@Sql(scripts = "/db/auth-users.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class ImageApiIntegrationTest {
	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired TokenService tokens;
	@Autowired JsonMapper mapper;
	@Autowired PlatformTransactionManager manager;
	@MockitoBean ImageStorage storage;
	@MockitoSpyBean UploadedImageRepository images;
	@Value("${local.server.port}") int port;
	byte[] png;

	@BeforeEach void setup() throws Exception {
		reset(storage, images);
		jdbc.update("delete from uploaded_images");
		jdbc.update("delete from users");
		jdbc.update("""
			insert into users (id,email,display_name,created_at,updated_at)
			values (42,'images@example.com','Images',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
			(43,'other@example.com','Other',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
			""");
		png = ImageTestFiles.image("png", 2, 2);
	}

	@ParameterizedTest @ValueSource(strings = {"PROFILE", "RECIPE", "RECEIPT"})
	void uploadsAndPersistsMetadata(String purpose) throws Exception {
		var result = mvc.perform(multipart("/api/me/images").file(file()).param("purpose", purpose)
				.param("userId", "43").header("Authorization", bearer(42)))
			.andExpect(status().isCreated()).andExpect(jsonPath("$.data.purpose").value(purpose))
			.andExpect(jsonPath("$.data.contentType").value("image/png"))
			.andExpect(jsonPath("$.data.sizeBytes").value(png.length))
			.andExpect(jsonPath("$.data.bucket").doesNotExist())
			.andExpect(jsonPath("$.data.objectKey").doesNotExist());
		if (purpose.equals("RECEIPT")) result.andExpect(jsonPath("$.data.url").value(nullValue()))
			.andExpect(jsonPath("$.data.visibility").value("PRIVATE"));
		else result.andExpect(jsonPath("$.data.url").value(org.hamcrest.Matchers.startsWith("https://images.example.com/images/42/")))
			.andExpect(jsonPath("$.data.visibility").value("PUBLIC"));
		var image = images.findAll().getFirst();
		assertThat(image.getUserId()).isEqualTo(42L);
		assertThat(image.getBucket()).isEqualTo(purpose.equals("RECEIPT") ? "test-private" : "test-public");
		assertThat(image.getObjectKey()).matches("images/42/[0-9a-f-]{36}\\.png");
		assertThat(image.isReady()).isTrue();
		verify(storage).put(any(), eq(png));
	}

	@Test void storesImageWithoutMetadata() throws Exception {
		var text = "tEXtComment\0SECRET".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
		var crc = new java.util.zip.CRC32();
		crc.update(text);
		var body = new ByteArrayOutputStream();
		body.write(png, 0, 33);
		body.write(java.nio.ByteBuffer.allocate(4).putInt(text.length - 4).array());
		body.write(text);
		body.write(java.nio.ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
		body.write(png, 33, png.length - 33);
		mvc.perform(multipart("/api/me/images").file(new MockMultipartFile("file", "x.png", "image/png", body.toByteArray()))
				.param("purpose", "PROFILE").header("Authorization", bearer(42)))
			.andExpect(status().isCreated()).andExpect(jsonPath("$.data.sizeBytes").value(png.length));
		verify(storage).put(any(), eq(png));
	}

	@Test void issuesPrivateUrlOnlyToOwnerWithNoCache() throws Exception {
		long id = upload("RECEIPT");
		when(storage.access(any())).thenReturn(new ImageAccessResponse(id, "https://example.com/signed", Instant.now().plusSeconds(300)));
		mvc.perform(get("/api/me/images/{id}/access-url", id).header("Authorization", bearer(42)))
			.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.data.imageId").value(id)).andExpect(jsonPath("$.data.expiresAt").exists());
		clearInvocations(storage);
		mvc.perform(get("/api/me/images/{id}/access-url", id).header("Authorization", bearer(43)))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("IMAGE_NOT_FOUND"));
		verifyNoInteractions(storage);
	}

	@Test void publicAndMissingImagesCannotBePresigned() throws Exception {
		long id = upload("PROFILE");
		clearInvocations(storage);
		for (long imageId : new long[]{id, 999999}) {
			mvc.perform(get("/api/me/images/{id}/access-url", imageId).header("Authorization", bearer(42)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("IMAGE_NOT_FOUND"));
		}
		verifyNoInteractions(storage);
	}

	@Test void unauthorizedRequestsDoNotWriteAnything() throws Exception {
		mvc.perform(multipart("/api/me/images").file(file()).param("purpose", "PROFILE"))
			.andExpect(status().isUnauthorized());
		mvc.perform(get("/api/me/images/1/access-url")).andExpect(status().isUnauthorized());
		assertThat(images.count()).isZero(); verifyNoInteractions(storage);
	}

	@Test void deletedAccountCannotUpload() throws Exception {
		jdbc.update("update users set deleted_at = CURRENT_TIMESTAMP where id=42");
		mvc.perform(multipart("/api/me/images").file(file()).param("purpose", "PROFILE").header("Authorization", bearer(42)))
			.andExpect(status().isUnauthorized());
		verifyNoInteractions(storage);
	}

	@ParameterizedTest @ValueSource(strings = {"", "private", "UNKNOWN"})
	void rejectsInvalidPurpose(String purpose) throws Exception {
		mvc.perform(multipart("/api/me/images").file(file()).param("purpose", purpose).header("Authorization", bearer(42)))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_IMAGE"));
		verifyNoInteractions(storage);
	}

	@Test void rejectsMissingAndDuplicateParts() throws Exception {
		mvc.perform(multipart("/api/me/images").param("purpose", "PROFILE").header("Authorization", bearer(42)))
			.andExpect(status().isBadRequest());
		mvc.perform(multipart("/api/me/images").file(file()).header("Authorization", bearer(42)))
			.andExpect(status().isBadRequest());
		mvc.perform(multipart("/api/me/images").file(file()).file(file()).param("purpose", "PROFILE").header("Authorization", bearer(42)))
			.andExpect(status().isBadRequest());
		verifyNoInteractions(storage);
	}

	@Test void invalidIdIsBadRequest() throws Exception {
		for (String id : new String[]{"0", "-1", "abc"})
			mvc.perform(get("/api/me/images/{id}/access-url", id).header("Authorization", bearer(42)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test void s3FailureLeavesDurablePendingRowAndItCannotBeRead() throws Exception {
		doThrow(new BusinessException(ErrorCode.IMAGE_STORAGE_UNAVAILABLE)).when(storage).put(any(), any());
		mvc.perform(multipart("/api/me/images").file(file()).param("purpose", "RECEIPT").header("Authorization", bearer(42)))
			.andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("IMAGE_STORAGE_UNAVAILABLE"));
		var pending = images.findAll().getFirst();
		assertThat(pending.isReady()).isFalse();
		mvc.perform(get("/api/me/images/{id}/access-url", pending.getId()).header("Authorization", bearer(42)))
			.andExpect(status().isNotFound());
	}

	@Test void dbFailureAfterPutRetainsCleanupRecordAndRetriesDelete() throws Exception {
		doThrow(new org.springframework.dao.DataAccessResourceFailureException("simulated DB outage"))
			.when(images).saveAndFlush(argThat((UploadedImage image) -> image != null && image.isReady()));
		mvc.perform(multipart("/api/me/images").file(file()).param("purpose", "PROFILE").header("Authorization", bearer(42)))
			.andExpect(status().isInternalServerError());
		verify(storage).put(any(), any());
		assertThat(images.findAll()).hasSize(1).allMatch(i -> !i.isReady());
		var cleanup = new ImageCleanup(images, storage, Clock.fixed(Instant.now().plusSeconds(7200), ZoneOffset.UTC), manager);
		doThrow(new IllegalStateException("denied")).when(storage).delete(any());
		cleanup.cleanPending();
		assertThat(images.count()).isEqualTo(1);
		doNothing().when(storage).delete(any());
		cleanup.cleanPending();
		assertThat(images.count()).isZero();
	}

	@Test void cleanupNeverDeletesSuccessfulUploads() throws Exception {
		upload("PROFILE");
		new ImageCleanup(images, storage, Clock.fixed(Instant.now().plusSeconds(7200), ZoneOffset.UTC), manager).cleanPending();
		assertThat(images.count()).isEqualTo(1);
		verify(storage, never()).delete(any());
	}

	@Test void realHttpUploadAndContainerSizeLimit() throws Exception {
		assertThat(httpUpload(png).statusCode()).isEqualTo(201);
		var oversized = httpUpload(new byte[5 * 1024 * 1024 + 1]);
		assertThat(oversized.statusCode()).isEqualTo(413);
		assertThat(oversized.body()).contains("IMAGE_TOO_LARGE");
	}

	@Test void invalidFilesReturnDocumentedErrorsWithoutStoring() throws Exception {
		mvc.perform(multipart("/api/me/images").file(new MockMultipartFile("file", "x.png", "image/png", new byte[]{1,2,3}))
			.param("purpose", "PROFILE").header("Authorization", bearer(42)))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_IMAGE"));
		mvc.perform(multipart("/api/me/images").file(new MockMultipartFile("file", "x.svg", "image/svg+xml", "<svg/>".getBytes()))
			.param("purpose", "PROFILE").header("Authorization", bearer(42)))
			.andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.error.code").value("UNSUPPORTED_IMAGE_TYPE"));
		assertThat(images.count()).isZero();
		verify(storage, never()).put(any(), any());
	}

	@Test void separateUploadsNeverOverwriteKeys() throws Exception {
		assertThat(upload("PROFILE")).isNotEqualTo(upload("PROFILE"));
		assertThat(images.findAll().stream().map(UploadedImage::getObjectKey)).doesNotHaveDuplicates();
	}

	@Test void firstDatabaseFailureNeverUploads() throws Exception {
		doThrow(new org.springframework.dao.DataAccessResourceFailureException("DB unavailable"))
			.when(images).saveAndFlush(any());
		mvc.perform(multipart("/api/me/images").file(file()).param("purpose", "PROFILE").header("Authorization", bearer(42)))
			.andExpect(status().isInternalServerError());
		verify(storage, never()).put(any(), any());
		assertThat(images.count()).isZero();
	}

	@Test void cleanupSkipsRecentPendingAndRechecksLockedStatus() throws Exception {
		doThrow(new BusinessException(ErrorCode.IMAGE_STORAGE_UNAVAILABLE)).when(storage).put(any(), any());
		mvc.perform(multipart("/api/me/images").file(file()).param("purpose", "RECEIPT").header("Authorization", bearer(42)))
			.andExpect(status().isServiceUnavailable());
		var pending = images.findAll().getFirst();
		new ImageCleanup(images, storage, Clock.systemUTC(), manager).cleanPending();
		verify(storage, never()).delete(any());
		// A candidate may become ready after the batch selection but before acquiring its lock.
		jdbc.update("update uploaded_images set ready = true where id = ?", pending.getId());
		doReturn(java.util.List.of(pending)).when(images).findTop20ByReadyFalseAndCreatedAtBeforeOrderByCreatedAtAsc(any());
		new ImageCleanup(images, storage, Clock.fixed(Instant.now().plusSeconds(7200), ZoneOffset.UTC), manager).cleanPending();
		verify(storage, never()).delete(any());
		assertThat(images.findById(pending.getId()).orElseThrow().isReady()).isTrue();
	}

	@Test void signingFailureReturnsServiceUnavailable() throws Exception {
		long id = upload("RECEIPT");
		when(storage.access(any())).thenThrow(new BusinessException(ErrorCode.IMAGE_STORAGE_UNAVAILABLE));
		mvc.perform(get("/api/me/images/{id}/access-url", id).header("Authorization", bearer(42)))
			.andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("IMAGE_STORAGE_UNAVAILABLE"));
	}

	private HttpResponse<String> httpUpload(byte[] bytes) throws Exception {
		var body = new ByteArrayOutputStream();
		body.write(("--testboundary\r\nContent-Disposition: form-data; name=\"purpose\"\r\n\r\nPROFILE\r\n"
			+ "--testboundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"test.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes());
		body.write(bytes); body.write("\r\n--testboundary--\r\n".getBytes());
		var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/me/images"))
			.header("Authorization", bearer(42)).header("Content-Type", "multipart/form-data; boundary=testboundary")
			.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
		try (var client = HttpClient.newHttpClient()) { return client.send(request, HttpResponse.BodyHandlers.ofString()); }
	}
	private long upload(String purpose) throws Exception {
		var response = mvc.perform(multipart("/api/me/images").file(file()).param("purpose", purpose)
			.header("Authorization", bearer(42))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return mapper.readTree(response).path("data").path("imageId").asLong();
	}
	private MockMultipartFile file() { return new MockMultipartFile("file", "../../unsafe.png", "image/png", png); }
	private String bearer(long userId) { return "Bearer " + tokens.issue(userId).accessToken(); }
}
