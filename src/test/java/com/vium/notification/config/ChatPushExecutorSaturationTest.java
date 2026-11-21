package com.vium.notification.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.vium.chat.service.ChatMessageSentEvent;
import com.vium.notification.service.FcmPushClient;
import com.vium.notification.service.PushResult;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * chatPushExecutor를 core=1/max=1/queue=0으로 극단적으로 줄인 전용 컨텍스트에서, 풀이 포화되면
 * 요청(이벤트 발행)은 예외 없이 정상 반환되고 두 번째 작업만 조용히 버려지는지 확인한다.
 */
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:chat-push-saturation-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
	"push.chat.executor.core-size=1",
	"push.chat.executor.max-size=1",
	"push.chat.executor.queue-capacity=0"
})
@Sql(scripts = "/db/auth-users.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class ChatPushExecutorSaturationTest {

	private static final Long SELLER_ID = 43L;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ApplicationEventPublisher eventPublisher;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@MockitoBean
	private FcmPushClient fcmPushClient;

	private ListAppender<ILoggingEvent> logAppender;

	@BeforeEach
	void setUp() {
		jdbc.update("delete from \"user_push_tokens\"");
		jdbc.update("delete from \"users\"");
		jdbc.update("""
			insert into "users" (id, email, display_name, created_at, updated_at)
			values (?, 'seller@example.com', '판매자', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""", SELLER_ID);
		jdbc.update("""
			insert into "user_push_tokens" (user_id, token, platform, created_at, updated_at)
			values (?, 'seller-token', 'ANDROID', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""", SELLER_ID);

		logAppender = new ListAppender<>();
		logAppender.start();
		((Logger) LoggerFactory.getLogger(ChatPushExecutorConfig.class)).addAppender(logAppender);
	}

	@AfterEach
	void tearDown() {
		((Logger) LoggerFactory.getLogger(ChatPushExecutorConfig.class)).detachAppender(logAppender);
	}

	private void publishInCommittedTransaction(ChatMessageSentEvent event) {
		new TransactionTemplate(transactionManager).execute(status -> {
			eventPublisher.publishEvent(event);
			return null;
		});
	}

	@Test
	void overflowingTaskIsDroppedWithWarnLogWhileRequestStillSucceeds() throws Exception {
		CountDownLatch firstCallStarted = new CountDownLatch(1);
		CountDownLatch releaseFirstCall = new CountDownLatch(1);
		when(fcmPushClient.send(any(), any(), any(), any())).thenAnswer(invocation -> {
			firstCallStarted.countDown();
			releaseFirstCall.await(5, TimeUnit.SECONDS);
			return PushResult.SUCCESS;
		});

		publishInCommittedTransaction(new ChatMessageSentEvent(1L, SELLER_ID));
		assertThat(firstCallStarted.await(2, TimeUnit.SECONDS)).isTrue();

		publishInCommittedTransaction(new ChatMessageSentEvent(2L, SELLER_ID));

		releaseFirstCall.countDown();
		verify(fcmPushClient, timeout(2000).times(1)).send(any(), any(), any(), any());

		boolean warnLogged = logAppender.list.stream()
			.anyMatch(event -> event.getLevel() == Level.WARN && event.getFormattedMessage().contains("스레드풀 포화"));
		assertThat(warnLogged).isTrue();
	}
}
