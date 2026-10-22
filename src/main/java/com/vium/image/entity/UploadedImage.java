package com.vium.image.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "uploaded_images")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UploadedImage {
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(nullable = false) private Long userId;
	@Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
	private ImagePurpose purpose;
	@Column(nullable = false, length = 63) private String bucket;
	@Column(nullable = false, unique = true, length = 200) private String objectKey;
	@Column(nullable = false, length = 30) private String contentType;
	@Column(nullable = false) private long sizeBytes;
	@Column(nullable = false) private Instant createdAt;
	@Column(nullable = false) private boolean ready;

	public static UploadedImage pending(Long userId, ImagePurpose purpose, String bucket,
			String key, String contentType, long size, Instant now) {
		var image = new UploadedImage();
		image.userId = userId;
		image.purpose = purpose;
		image.bucket = bucket;
		image.objectKey = key;
		image.contentType = contentType;
		image.sizeBytes = size;
		image.createdAt = now;
		return image;
	}

	public void markReady() { ready = true; }
}
