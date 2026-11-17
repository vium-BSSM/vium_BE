package com.vium.post.service;

import com.vium.post.config.PostExpiryProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 게시물 만료 배치의 정기 실행을 담당한다. AutoDisposeScheduler와 같은 방식:
 * post.expiry.enabled=true일 때만 빈으로 등록되고(기본 꺼짐), 자동 폐기보다 먼저 돌도록
 * 기본 cron을 자정(0 0 0 * * *)으로 둔다(자동 폐기 기본은 0 10 0 * * *).
 */
@Component
@ConditionalOnProperty(name = "post.expiry.enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(PostExpiryProperties.class)
@RequiredArgsConstructor
@Slf4j
public class PostExpiryScheduler {

	private final PostExpiryService postExpiryService;
	private final PostExpiryProperties properties;

	@PostConstruct
	void logActivation() {
		log.info("게시물 만료 스케줄러 활성화: cron={}, zone={}", properties.cron(), properties.timeZone());
	}

	@Scheduled(cron = "${post.expiry.cron}", zone = "${post.expiry.time-zone}")
	public void run() {
		postExpiryService.expireOpenPosts();
	}
}
