package com.vium.image.dto;

import com.vium.image.entity.ImagePurpose;
import java.time.Instant;

public record ImageUploadResponse(Long imageId, ImagePurpose purpose, String visibility,
	String contentType, long sizeBytes, String url, Instant createdAt) {}
