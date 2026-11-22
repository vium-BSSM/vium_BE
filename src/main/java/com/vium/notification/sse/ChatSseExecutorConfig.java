package com.vium.notification.sse;

import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 채팅 SSE 브로드캐스트 전용 풀. chatPushExecutor(FCM)와 분리해 둘 중 하나가 느려지거나 포화돼도
 * 서로 영향을 주지 않는다. 포화 시(큐+풀 모두 가득 참) 예외 없이 WARN 로그만 남기고 작업을 버린다.
 * 로그에는 활성 스레드 수·큐 크기만 남기고 사용자 ID 등 식별자는 담지 않는다.
 */
@Configuration
@EnableConfigurationProperties(SseProperties.class)
@Slf4j
public class ChatSseExecutorConfig {

	@Bean(name = "chatSseExecutor")
	public Executor chatSseExecutor(SseProperties properties) {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(properties.executor().coreSize());
		executor.setMaxPoolSize(properties.executor().maxSize());
		executor.setQueueCapacity(properties.executor().queueCapacity());
		executor.setThreadNamePrefix("chat-sse-");
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(5);
		executor.setRejectedExecutionHandler((runnable, threadPoolExecutor) ->
			log.warn("채팅 SSE 브로드캐스트 작업이 스레드풀 포화로 버려졌습니다: activeCount={}, queueSize={}",
				threadPoolExecutor.getActiveCount(), threadPoolExecutor.getQueue().size()));
		executor.initialize();
		return executor;
	}
}
