package com.vium.image.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
@EnableConfigurationProperties(ImageStorageProperties.class)
public class ImageStorageConfig {
	@Configuration
	@EnableScheduling
	@ConditionalOnProperty(name = "images.storage.enabled", havingValue = "true")
	static class Enabled {
		@Bean(destroyMethod = "close")
		DefaultCredentialsProvider imageCredentials(ImageStorageProperties p) {
			var url = URI.create(p.publicBaseUrl());
			if (!p.privateBucket().matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
				|| !p.publicBucket().matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
				|| p.privateBucket().equals(p.publicBucket())
				|| !"https".equals(url.getScheme()) || url.getHost() == null
				|| url.getUserInfo() != null || url.getQuery() != null || url.getFragment() != null
				|| !(url.getPath().isEmpty() || url.getPath().equals("/"))) {
				throw new IllegalArgumentException("Configure distinct image buckets and an HTTPS public base URL");
			}
			return DefaultCredentialsProvider.builder().build();
		}

		@Bean(destroyMethod = "close")
		S3Client imageS3Client(ImageStorageProperties p, DefaultCredentialsProvider imageCredentials) {
			return S3Client.builder().region(Region.of(p.region())).credentialsProvider(imageCredentials)
				.httpClientBuilder(UrlConnectionHttpClient.builder()
					.connectionTimeout(Duration.ofSeconds(5)).socketTimeout(Duration.ofSeconds(20)))
				.overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(45))
					.apiCallAttemptTimeout(Duration.ofSeconds(25)))
				.build();
		}

		@Bean(destroyMethod = "close")
		S3Presigner imageS3Presigner(ImageStorageProperties p, DefaultCredentialsProvider imageCredentials) {
			return S3Presigner.builder().region(Region.of(p.region())).credentialsProvider(imageCredentials).build();
		}
	}
}
