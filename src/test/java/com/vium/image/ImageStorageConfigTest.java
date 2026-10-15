package com.vium.image;

import static org.assertj.core.api.Assertions.*;
import com.vium.image.config.ImageStorageConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class ImageStorageConfigTest {
	private final ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(ImageStorageConfig.class);

	@Test void disabledDoesNotCreateAwsClients() {
		context.run(c -> assertThat(c).doesNotHaveBean(S3Client.class).doesNotHaveBean(S3Presigner.class));
	}
	@Test void enabledCreatesClientsWithoutResolvingCredentials() {
		context.withPropertyValues("images.storage.enabled=true", "images.storage.private-bucket=test-private",
			"images.storage.public-bucket=test-public", "images.storage.public-base-url=https://cdn.example.com")
			.run(c -> assertThat(c).hasSingleBean(S3Client.class).hasSingleBean(S3Presigner.class));
	}
	@Test void refusesSamePublicAndPrivateBucket() {
		context.withPropertyValues("images.storage.enabled=true", "images.storage.private-bucket=test-private",
			"images.storage.public-bucket=test-private", "images.storage.public-base-url=https://cdn.example.com")
			.run(c -> assertThat(c).hasFailed());
	}
	@Test void refusesMissingSettings() {
		context.withPropertyValues("images.storage.enabled=true").run(c -> assertThat(c).hasFailed());
	}
}
