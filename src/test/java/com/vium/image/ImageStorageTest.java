package com.vium.image;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.image.config.ImageStorageProperties;
import com.vium.image.entity.ImagePurpose;
import com.vium.image.entity.UploadedImage;
import com.vium.image.service.ImageStorage;
import java.time.Instant;
import java.time.Duration;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class ImageStorageTest {
	@SuppressWarnings("unchecked")
	private final ObjectProvider<S3Client> clients = mock(ObjectProvider.class);
	@SuppressWarnings("unchecked")
	private final ObjectProvider<S3Presigner> presigners = mock(ObjectProvider.class);
	private final S3Client client = mock(S3Client.class);
	private final ImageStorageProperties enabled = new ImageStorageProperties(true, "ap-northeast-2", "private", "public", "https://cdn.example.com");

	@Test void presignsLocallyWithFiveMinuteExpiryAndNoCache() {
		try (var presigner = S3Presigner.builder().region(Region.AP_NORTHEAST_2)
			.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test-access", "test-secret"))).build()) {
			when(presigners.getObject()).thenReturn(presigner);
			var result = new ImageStorage(enabled, clients, presigners).access(image(ImagePurpose.RECEIPT));
			assertThat(result.url()).contains("X-Amz-Expires=300", "response-cache-control=private%2C%20no-store", "/images/42/test.png");
			assertThat(Duration.between(Instant.now(), result.expiresAt()).getSeconds()).isBetween(295L, 300L);
			verifyNoInteractions(clients);
		}
	}

	@Test void putsValidatedBytesWithExplicitMimeAndNoAcl() throws Exception {
		when(clients.getObject()).thenReturn(client);
		byte[] bytes = ImageTestFiles.image("png", 2, 2);
		doAnswer(invocation -> {
			Consumer<PutObjectRequest.Builder> builder = invocation.getArgument(0);
			var b = PutObjectRequest.builder(); builder.accept(b);
			var request = b.build();
			assertThat(request.bucket()).isEqualTo("private");
			assertThat(request.contentType()).isEqualTo("image/png");
			assertThat(request.cacheControl()).isEqualTo("private, no-store");
			assertThat(request.acl()).isNull();
			RequestBody body = invocation.getArgument(1);
			try (var in = body.contentStreamProvider().newStream()) { assertThat(in.readAllBytes()).isEqualTo(bytes); }
			return null;
		}).when(client).putObject(any(Consumer.class), any(RequestBody.class));
		new ImageStorage(enabled, clients, presigners).put(image(ImagePurpose.RECEIPT), bytes);
	}

	@Test void disabledStorageDoesNotResolveCredentials() {
		var disabled = new ImageStorage(new ImageStorageProperties(false, "ap-northeast-2", "", "", ""), clients, presigners);
		assertThatThrownBy(disabled::requireEnabled).isInstanceOfSatisfying(BusinessException.class,
			e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.IMAGE_STORAGE_UNAVAILABLE));
		verifyNoInteractions(clients, presigners);
	}

	@Test void hidesSdkFailureDetails() {
		when(presigners.getObject()).thenThrow(new IllegalStateException("sensitive provider details"));
		assertThatThrownBy(() -> new ImageStorage(enabled, clients, presigners).access(image(ImagePurpose.RECEIPT)))
			.isInstanceOfSatisfying(BusinessException.class, e -> {
				assertThat(e.getErrorCode()).isEqualTo(ErrorCode.IMAGE_STORAGE_UNAVAILABLE);
				assertThat(e.getMessage()).doesNotContain("sensitive");
			});
	}

	private UploadedImage image(ImagePurpose purpose) {
		return UploadedImage.pending(42L, purpose, "private", "images/42/test.png", "image/png", 100, Instant.now());
	}
}
