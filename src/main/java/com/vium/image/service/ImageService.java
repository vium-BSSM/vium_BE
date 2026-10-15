package com.vium.image.service;

import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.image.config.ImageStorageProperties;
import com.vium.image.dto.ImageUploadResponse;
import com.vium.image.dto.ImageAccessResponse;
import com.vium.image.entity.ImagePurpose;
import com.vium.image.entity.UploadedImage;
import com.vium.image.repository.UploadedImageRepository;
import com.vium.user.service.UserProfileService;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ImageService {
	private final UploadedImageRepository images;
	private final ImageStorage storage;
	private final ImageValidator validator;
	private final ImageMetadataStripper metadata;
	private final ImageStorageProperties properties;
	private final UserProfileService users;
	private final Clock clock;
	private final TransactionTemplate transactions;
	// Bound memory on the 512MiB production container (~256MiB heap); excess requests retry later.
	// Each upload holds up to ~10MiB of bytes until S3 finishes; decoding needs up to ~64MiB more.
	private final Semaphore uploadSlots = new Semaphore(4);
	private final Semaphore decodeSlot = new Semaphore(1);

	public ImageService(UploadedImageRepository images, ImageStorage storage, ImageValidator validator,
		ImageMetadataStripper metadata, ImageStorageProperties properties, UserProfileService users, Clock clock,
		PlatformTransactionManager manager) {
		this.images = images; this.storage = storage; this.validator = validator; this.metadata = metadata;
		this.properties = properties; this.users = users; this.clock = clock;
		this.transactions = new TransactionTemplate(manager);
	}

	public ImageUploadResponse upload(Long userId, ImagePurpose purpose, MultipartFile file) {
		users.getProfile(userId);
		storage.requireEnabled();
		acquire(uploadSlots);
		try {
			return store(userId, purpose, file);
		} finally {
			uploadSlots.release();
		}
	}

	private ImageUploadResponse store(Long userId, ImagePurpose purpose, MultipartFile file) {
		var validated = decode(file);
		byte[] bytes = metadata.strip(validated.bytes(), validated.contentType());
		String bucket = purpose.isPrivate() ? properties.privateBucket() : properties.publicBucket();
		String key = "images/" + userId + "/" + UUID.randomUUID() + "." + validated.extension();
		// Commit a durable cleanup record BEFORE S3 can contain an object.
		var pending = transactions.execute(s -> images.saveAndFlush(UploadedImage.pending(userId,
			purpose, bucket, key, validated.contentType(), bytes.length, clock.instant())));
		// Outside a transaction so slow S3 calls never pin DB connections.
		// Cleanup only touches rows an hour old, far past the bounded S3 timeout.
		storage.put(pending, bytes);
		var ready = transactions.execute(s -> {
			var image = images.lockById(pending.getId())
				.orElseThrow(() -> new BusinessException(ErrorCode.IMAGE_STORAGE_UNAVAILABLE));
			image.markReady();
			return images.saveAndFlush(image);
		});
		String url = purpose.isPrivate() ? null : properties.publicBaseUrl().replaceAll("/$", "") + "/" + key;
		return new ImageUploadResponse(ready.getId(), purpose, purpose.isPrivate() ? "PRIVATE" : "PUBLIC",
			ready.getContentType(), ready.getSizeBytes(), url, ready.getCreatedAt());
	}

	private ImageValidator.Validated decode(MultipartFile file) {
		acquire(decodeSlot);
		try {
			return validator.validate(file);
		} finally {
			decodeSlot.release();
		}
	}

	private static void acquire(Semaphore slot) {
		try {
			if (!slot.tryAcquire(3, TimeUnit.SECONDS))
				throw new BusinessException(ErrorCode.IMAGE_STORAGE_UNAVAILABLE);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new BusinessException(ErrorCode.IMAGE_STORAGE_UNAVAILABLE);
		}
	}

	@Transactional(readOnly = true)
	public ImageAccessResponse access(Long userId, Long id) {
		users.getProfile(userId);
		if (id <= 0) throw new BusinessException(ErrorCode.INVALID_REQUEST);
		var image = images.findByIdAndUserIdAndPurposeAndReadyTrue(id, userId, ImagePurpose.RECEIPT)
			.orElseThrow(() -> new BusinessException(ErrorCode.IMAGE_NOT_FOUND));
		return storage.access(image);
	}
}
