package com.vium.image.dto;

import java.time.Instant;

public record ImageAccessResponse(Long imageId, String url, Instant expiresAt) {}
