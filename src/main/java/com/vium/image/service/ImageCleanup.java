package com.vium.image.service;

import com.vium.image.repository.UploadedImageRepository;
import java.time.Clock;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Slf4j
@ConditionalOnProperty(name = "images.storage.enabled", havingValue = "true")
public class ImageCleanup {
	private final UploadedImageRepository images;
	private final ImageStorage storage;
	private final Clock clock;
	private final TransactionTemplate transactions;

	public ImageCleanup(UploadedImageRepository images, ImageStorage storage, Clock clock,
		PlatformTransactionManager manager) {
		this.images = images; this.storage = storage; this.clock = clock;
		this.transactions = new TransactionTemplate(manager);
	}

	@Scheduled(initialDelay = 60000, fixedDelay = 60000)
	public void cleanPending() {
		// Wait past the bounded S3 timeout, including possible ambiguous remote writes.
		var cutoff = clock.instant().minus(Duration.ofHours(1));
		for (var candidate : images.findTop20ByReadyFalseAndCreatedAtBeforeOrderByCreatedAtAsc(cutoff)) {
			try {
				transactions.executeWithoutResult(s -> images.lockById(candidate.getId()).ifPresent(image -> {
					if (!image.isReady() && image.getCreatedAt().isBefore(cutoff)) {
						storage.delete(image);
						images.delete(image);
					}
				}));
			} catch (RuntimeException e) {
				// Keep the committed pending row for the next run; never log credentials/URLs.
				log.warn("Image cleanup will retry: imageId={}, failure={}", candidate.getId(), e.getClass().getSimpleName());
			}
		}
	}
}
