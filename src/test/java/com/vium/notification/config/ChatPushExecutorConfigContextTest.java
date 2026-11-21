package com.vium.notification.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * chatPushExecutor(Executor 타입 빈) 도입으로 Spring Boot의 applicationTaskExecutor 자동 구성이
 * @ConditionalOnMissingBean(Executor.class)에 걸려 물러나는 문제를 ChatPushExecutorConfig가 직접
 * 복원한다. 향후 다른 Executor 빈이 추가되어 이 복원이 깨지는 것을 막기 위한 회귀 테스트다.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:chat-push-executor-context-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class ChatPushExecutorConfigContextTest {

	@Autowired
	private ApplicationContext context;

	@Test
	void applicationTaskExecutorChatPushExecutorAndTaskSchedulerAllExist() {
		assertThat(context.getBeanNamesForType(Executor.class))
			.containsExactlyInAnyOrder("chatPushExecutor", "applicationTaskExecutor", "taskScheduler");
	}
}
