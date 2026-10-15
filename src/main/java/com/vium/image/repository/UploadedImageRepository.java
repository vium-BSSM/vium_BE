package com.vium.image.repository;

import com.vium.image.entity.UploadedImage;
import com.vium.image.entity.ImagePurpose;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UploadedImageRepository extends JpaRepository<UploadedImage, Long> {
	Optional<UploadedImage> findByIdAndUserIdAndPurposeAndReadyTrue(Long id, Long userId, ImagePurpose purpose);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select i from UploadedImage i where i.id = :id")
	Optional<UploadedImage> lockById(@Param("id") Long id);

	List<UploadedImage> findTop20ByReadyFalseAndCreatedAtBeforeOrderByCreatedAtAsc(Instant cutoff);
}
