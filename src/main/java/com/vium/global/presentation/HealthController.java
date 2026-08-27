package com.vium.global.presentation;

import java.time.Instant;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/health")
public class HealthController {

	@GetMapping
	public HealthResponse health() {
		return new HealthResponse("OK", Instant.now());
	}

	public record HealthResponse(String status, Instant timestamp) {
	}
}
