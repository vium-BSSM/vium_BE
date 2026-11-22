package com.vium.notification.sse;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * SseHeartbeatScheduler는 heartbeat/최대 수명 체크용 ScheduledExecutorService를 private 필드로만
 * 두고 Spring 빈으로 등록하지 않는다. 등록하면 Spring Boot TaskSchedulingConfigurations$TaskSchedulerConfiguration의
 * @ConditionalOnMissingBean({TaskScheduler.class, ScheduledExecutorService.class})에 걸려 기존
 * 배치 스케줄러들이 공유하는 taskScheduler 빈(spring.task.scheduling.pool.size=1) 자체가 생성되지
 * 않는 회귀가 생긴다. 이 테스트는 그 분리가 유지되는지 고정한다.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:sse-scheduler-isolation-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class SseSchedulerIsolationContextTest {

	@Autowired
	private ApplicationContext context;

	@Test
	void taskSchedulerBeanExistsWithConfiguredPoolSizeOne() {
		assertThat(context.getBeanNamesForType(TaskScheduler.class)).containsExactly("taskScheduler");
		TaskScheduler taskScheduler = context.getBean("taskScheduler", TaskScheduler.class);
		assertThat(taskScheduler).isInstanceOf(ThreadPoolTaskScheduler.class);
		// getPoolSize()는 현재 살아있는 스레드 수(지연 생성이라 아직 0일 수 있음)를 반환하므로, 설정값은
		// 코어 풀 크기로 확인한다.
		assertThat(((ThreadPoolTaskScheduler) taskScheduler).getScheduledThreadPoolExecutor().getCorePoolSize())
			.isEqualTo(1);
	}

	@Test
	void noScheduledExecutorServiceBeanRegistered() {
		assertThat(context.getBeanNamesForType(ScheduledExecutorService.class)).isEmpty();
	}
}
