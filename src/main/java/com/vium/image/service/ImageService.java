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
	private final ImageStorageProperties properties;
	private final UserProfileService users;
	private final Clock clock;
	private final TransactionTemplate transactions;
	// Bound decoded image memory on the 512MiB production container; excess requests retry later.
	private final Semaphore uploadSlot = new Semaphore(1);

	public ImageService(UploadedImageRepository images, ImageStorage storage, ImageValidator validator,
		ImageStorageProperties properties, UserProfileService users, Clock clock, PlatformTransactionManager manager) {
		this.images = images; this.storage = storage; this.validator = validator;
		this.properties = properties; this.users = users; this.clock = clock;
		this.transactions = new TransactionTemplate(manager);
	}

	public ImageUploadResponse upload(Long userId, ImagePurpose purpose, MultipartFile file) {
		users.getProfile(userId);
		storage.requireEnabled();
		if (!uploadSlot.tryAcquire()) throw new BusinessException(ErrorCode.IMAGE_STORAGE_UNAVAILABLE);
		try {
			var validated = validator.validate(file);
			String bucket = purpose.isPrivate() ? properties.privateBucket() : properties.publicBucket();
			String key = "images/" + userId + "/" + UUID.randomUUID() + "." + validated.extension();
			// Commit a durable cleanup record BEFORE S3 can contain an object.
			var pending = transactions.execute(s -> images.saveAndFlush(UploadedImage.pending(userId,
				purpose, bucket, key, validated.contentType(), validated.bytes().length, clock.instant())));
			var ready = transactions.execute(s -> {
				// Cleanup takes the same lock: it cannot delete a concurrent successful upload.
				var image = images.lockById(pending.getId()).orElseThrow();
				storage.put(image, validated.bytes());
				image.markReady();
				return images.saveAndFlush(image);
			});
			String url = purpose.isPrivate() ? null : properties.publicBaseUrl().replaceAll("/$", "") + "/" + key;
			return new ImageUploadResponse(ready.getId(), purpose, purpose.isPrivate() ? "PRIVATE" : "PUBLIC",
				ready.getContentType(), ready.getSizeBytes(), url, ready.getCreatedAt());
		} finally {
			uploadSlot.release();
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
