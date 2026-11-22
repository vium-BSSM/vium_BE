package com.vium.notification.config;

import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 채팅 푸시 전용 풀. AsyncConfigurer는 구현하지 않는다 — 전역 디폴트 @Async executor를 바꾸지 않기 위해서다.
 * 이 풀을 쓰는 @Async 메서드는 반드시 @Async("chatPushExecutor")로 이름을 명시해야 한다.
 * 포화 시(큐+풀 모두 가득 참) 커스텀 핸들러가 예외 없이 WARN 로그만 남기고 작업을 버린다.
 * 로그에는 활성 스레드 수·큐 크기만 남기고 chatRoomId 등 개별 작업 식별자는 담지 않는다
 * (RejectedExecutionHandler는 제출된 Runnable만 받고 @Async 호출 인자에는 접근할 수 없다).
 *
 * chatPushExecutor(타입 Executor)를 빈으로 등록하면 Spring Boot TaskExecutionAutoConfiguration의
 * applicationTaskExecutor 빈이 @ConditionalOnMissingBean(Executor.class)에 걸려 물러난다(이름이 아니라
 * 타입 기준 조건). MVC 비동기 처리(SseEmitter 등)가 빈 이름 "applicationTaskExecutor"를 찾으므로, Boot가
 * 쓰는 것과 동일한 ThreadPoolTaskExecutorBuilder로 그 빈을 여기서 그대로 복원한다(Boot 기본값 그대로,
 * 별도 프로퍼티 없음).
 */
@Configuration
@EnableAsync
@EnableConfigurationProperties(ChatPushExecutorProperties.class)
@Slf4j
public class ChatPushExecutorConfig {

	@Bean(name = "applicationTaskExecutor")
	public ThreadPoolTaskExecutor applicationTaskExecutor(ThreadPoolTaskExecutorBuilder builder) {
		return builder.build();
	}

	@Bean(name = "chatPushExecutor")
	public Executor chatPushExecutor(ChatPushExecutorProperties properties) {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(properties.coreSize());
		executor.setMaxPoolSize(properties.maxSize());
		executor.setQueueCapacity(properties.queueCapacity());
		executor.setThreadNamePrefix("chat-push-");
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(5);
		executor.setRejectedExecutionHandler((runnable, threadPoolExecutor) ->
			log.warn("채팅 푸시 작업이 스레드풀 포화로 버려졌습니다: activeCount={}, queueSize={}",
				threadPoolExecutor.getActiveCount(), threadPoolExecutor.getQueue().size()));
		executor.initialize();
		return executor;
	}
}
