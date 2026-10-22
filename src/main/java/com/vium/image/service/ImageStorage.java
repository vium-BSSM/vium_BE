package com.vium.image.service;

import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.image.config.ImageStorageProperties;
import com.vium.image.dto.ImageAccessResponse;
import com.vium.image.entity.UploadedImage;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Service
@RequiredArgsConstructor
public class ImageStorage {
	private final ImageStorageProperties properties;
	private final ObjectProvider<S3Client> clients;
	private final ObjectProvider<S3Presigner> presigners;

	public void requireEnabled() {
		if (!properties.enabled()) throw new BusinessException(ErrorCode.IMAGE_STORAGE_UNAVAILABLE);
	}

	public void put(UploadedImage image, byte[] bytes) {
		requireEnabled();
		try {
			clients.getObject().putObject(b -> b.bucket(image.getBucket()).key(image.getObjectKey())
				.contentType(image.getContentType()).contentLength((long) bytes.length)
				.cacheControl(image.getPurpose().isPrivate() ? "private, no-store" : "public, max-age=31536000, immutable"),
				RequestBody.fromBytes(bytes));
		} catch (RuntimeException e) {
			throw new BusinessException(ErrorCode.IMAGE_STORAGE_UNAVAILABLE);
		}
	}

	public void delete(UploadedImage image) {
		requireEnabled();
		clients.getObject().deleteObject(b -> b.bucket(image.getBucket()).key(image.getObjectKey()));
	}

	public ImageAccessResponse access(UploadedImage image) {
		requireEnabled();
		try {
			var signed = presigners.getObject().presignGetObject(b -> b.signatureDuration(Duration.ofSeconds(300))
				.getObjectRequest(GetObjectRequest.builder().bucket(image.getBucket()).key(image.getObjectKey())
					.responseCacheControl("private, no-store").build()));
			return new ImageAccessResponse(image.getId(), signed.url().toString(), signed.expiration());
		} catch (RuntimeException e) {
			throw new BusinessException(ErrorCode.IMAGE_STORAGE_UNAVAILABLE);
		}
	}
}
